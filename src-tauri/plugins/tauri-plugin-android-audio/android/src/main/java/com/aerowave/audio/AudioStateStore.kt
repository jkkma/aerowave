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
  val sleepRevision: Long? = null,
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
  val playableUrl: String = "",
  val isHls: Boolean = false,
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
      sleepTimer.revision,
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
      positionMs = 0,
      playableUrl = request.url,
      isHls = request.isHls,
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
      playableUrl = "",
      isHls = false,
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
      playableUrl = prefs.getString("playableUrl", "") ?: "",
      isHls = prefs.getBoolean("isHls", false),
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
      .putString("playableUrl", state.playableUrl)
      .putBoolean("isHls", state.isHls)
      .apply()
  }
}
