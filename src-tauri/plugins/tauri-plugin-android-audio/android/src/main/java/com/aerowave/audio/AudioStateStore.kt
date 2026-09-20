package com.aerowave.audio

import android.content.Context

internal data class PlayRequest(
  val url: String,
  val sourceUrl: String,
  val title: String,
  val stationId: String?,
  val volume: Float,
  val generation: Long,
  val isHls: Boolean,
  val showMetadata: Boolean = true,
  val artworkDataUrl: String? = null,
  val sleepRevision: Long? = null,
  val sourceFolder: String? = null,
  val backupFolder: String? = null,
)

internal data class PlaybackSnapshot(
  val status: String = STATUS_IDLE,
  val generation: Long = 0,
  val sourceUrl: String = "",
  val title: String = "",
  val stationId: String? = null,
  val positionMs: Long = 0,
  val volume: Float = 1f,
  val error: String? = null,
  val trackTitle: String? = null,
  // Metadata continues to advance while display is disabled. Keeping that
  // value private lets re-enabling restore the current song immediately.
  val hiddenTrackTitle: String? = null,
  val playableUrl: String = "",
  val isHls: Boolean = false,
  val showMetadata: Boolean = true,
  // Kept out of the JS snapshot because even a bounded image is much too
  // large for the one-second state poll. It remains persisted for restoration.
  val artworkDataUrl: String? = null,
  val sourceFolder: String? = null,
  val backupFolder: String? = null,
  // Sleep state deliberately stays in process memory. Persisting elapsed
  // realtime deadlines would leave a stale timer after service/process death.
  val sleepTimer: SleepTimerSnapshot = SleepTimerSnapshot(),
) {
  fun requestOrNull(): PlayRequest? = playableUrl.takeIf { it.isNotBlank() }?.let {
    PlayRequest(
      it,
      sourceUrl,
      title,
      stationId,
      volume,
      generation,
      isHls,
      showMetadata,
      artworkDataUrl,
      sleepTimer.revision,
      sourceFolder,
      backupFolder,
    )
  }

}

internal const val STATUS_IDLE = "idle"
internal const val STATUS_BUFFERING = "buffering"
internal const val STATUS_PLAYING = "playing"
internal const val STATUS_PAUSED = "paused"
internal const val STATUS_ERROR = "error"

internal object AudioStateStore {
  private const val PREFS = "aerowave_android_audio"
  private val lock = Any()
  private var loaded = false
  private var restoredFromDisk = false
  private var current = PlaybackSnapshot()

  fun begin(context: Context, request: PlayRequest): PlaybackSnapshot = update(context) {
    it.copy(
      status = STATUS_BUFFERING,
      generation = request.generation,
      sourceUrl = request.sourceUrl,
      title = request.title,
      stationId = request.stationId,
      volume = request.volume,
      error = null,
      trackTitle = null,
      hiddenTrackTitle = null,
      positionMs = 0,
      playableUrl = request.url,
      isHls = request.isHls,
      showMetadata = request.showMetadata,
      artworkDataUrl = request.artworkDataUrl,
      sourceFolder = request.sourceFolder,
      backupFolder = request.backupFolder,
    )
  }

  fun snapshot(context: Context): PlaybackSnapshot = synchronized(lock) {
    ensureLoaded(context)
    current
  }

  fun restoredFromPreviousProcess(context: Context): Boolean = synchronized(lock) {
    ensureLoaded(context)
    restoredFromDisk
  }

  fun update(
    context: Context,
    transform: (PlaybackSnapshot) -> PlaybackSnapshot,
  ): PlaybackSnapshot = synchronized(lock) {
    ensureLoaded(context)
    current = transform(current)
    persist(context, current)
    current
  }

  fun stop(context: Context): PlaybackSnapshot = update(context) {
    it.copy(
      status = STATUS_IDLE,
      positionMs = 0,
      error = null,
      trackTitle = null,
      hiddenTrackTitle = null,
      playableUrl = "",
      isHls = false,
      showMetadata = true,
      artworkDataUrl = null,
      sourceFolder = null,
      backupFolder = null,
    )
  }

  private fun ensureLoaded(context: Context) {
    if (loaded) return
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    restoredFromDisk = prefs.contains("status")
    current = PlaybackSnapshot(
      status = prefs.getString("status", STATUS_IDLE) ?: STATUS_IDLE,
      generation = prefs.getLong("generation", 0),
      sourceUrl = prefs.getString("sourceUrl", "") ?: "",
      title = prefs.getString("title", "") ?: "",
      stationId = prefs.getString("stationId", null),
      positionMs = prefs.getLong("positionMs", 0),
      volume = prefs.getFloat("volume", 1f),
      error = prefs.getString("error", null),
      trackTitle = prefs.getString("trackTitle", null),
      hiddenTrackTitle = prefs.getString("hiddenTrackTitle", null),
      playableUrl = prefs.getString("playableUrl", "") ?: "",
      isHls = prefs.getBoolean("isHls", false),
      showMetadata = prefs.getBoolean("showMetadata", true),
      artworkDataUrl = prefs.getString("artworkDataUrl", null),
      sourceFolder = prefs.getString("sourceFolder", null),
      backupFolder = prefs.getString("backupFolder", null),
    )
    loaded = true
  }

  private fun persist(context: Context, state: PlaybackSnapshot) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString("status", state.status)
      .putLong("generation", state.generation)
      .putString("sourceUrl", state.sourceUrl)
      .putString("title", state.title)
      .putString("stationId", state.stationId)
      .putLong("positionMs", state.positionMs)
      .putFloat("volume", state.volume)
      .putString("error", state.error)
      .putString("trackTitle", state.trackTitle)
      .putString("hiddenTrackTitle", state.hiddenTrackTitle)
      .putString("playableUrl", state.playableUrl)
      .putBoolean("isHls", state.isHls)
      .putBoolean("showMetadata", state.showMetadata)
      .putString("artworkDataUrl", state.artworkDataUrl)
      .putString("sourceFolder", state.sourceFolder)
      .putString("backupFolder", state.backupFolder)
      .apply()
  }
}

internal fun PlaybackSnapshot.withMetadataEnabled(enabled: Boolean): PlaybackSnapshot =
  if (enabled) {
    copy(showMetadata = true, trackTitle = hiddenTrackTitle)
  } else {
    copy(
      showMetadata = false,
      trackTitle = null,
      hiddenTrackTitle = trackTitle ?: hiddenTrackTitle,
    )
  }

internal fun PlaybackSnapshot.withIncomingTrackTitle(title: String?): PlaybackSnapshot =
  if (showMetadata) {
    copy(trackTitle = title, hiddenTrackTitle = title)
  } else {
    copy(trackTitle = null, hiddenTrackTitle = title)
  }
