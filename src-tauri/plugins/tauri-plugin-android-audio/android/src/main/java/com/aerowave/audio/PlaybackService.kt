package com.aerowave.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService(), Player.Listener {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var player: ExoPlayer
  private lateinit var mediaSession: MediaSession
  private var currentRequest: PlayRequest? = null
  private var reconnectAttempts = 0
  private var pendingReconnect: Runnable? = null
  private var wantsPlayback = false
  private var desiredVolume = 1f
  private lateinit var sleepTimer: SleepTimerController
  private val sleepTimerTick = object : Runnable {
    override fun run() {
      if (expireSleepTimerIfNeeded()) return
      applyOutputVolume()
      scheduleSleepTimerTick()
    }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    val restored = AudioStateStore.snapshot(this)
    desiredVolume = restored.volume.coerceIn(0f, 1f)
    sleepTimer = SleepTimerController(
      initial = restored.sleepTimer,
      elapsedRealtimeMs = SystemClock::elapsedRealtime,
      wallTimeMs = System::currentTimeMillis,
    )

    val version = try {
      packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) {
      "0.0.0"
    }
    val userAgent = "Aerowave/$version"

    // The stream mode is captured when Media3 asks for each data source. HLS
    // stays public-only across its manifest, redirect, key and segment fetches;
    // the ordinary radio path additionally admits Aerowave's loopback relay.
    val dataSourceFactory = DataSource.Factory {
      val allowLoopback = currentRequest?.isHls == false
      OkHttpDataSource.Factory(NetworkGuard.client(allowLoopback, userAgent))
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        .createDataSource()
    }
    val mediaSourceFactory = DefaultMediaSourceFactory(this)
      .setDataSourceFactory(dataSourceFactory)
      // Reconnection is owned here so the attempt budget covers the whole
      // live stream rather than being reset independently for every segment.
      .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      .setUsage(C.USAGE_MEDIA)
      .build()

    player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(mediaSourceFactory)
      .setAudioAttributes(audioAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .build()
      .also {
        it.setWakeMode(C.WAKE_MODE_NETWORK)
        it.addListener(this)
      }

    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    @Suppress("DEPRECATION")
    val sessionPlayer = object : ForwardingPlayer(player) {
      override fun play() {
        this@PlaybackService.resumePlayback()
      }

      override fun pause() {
        this@PlaybackService.pausePlayback()
      }

      override fun stop() {
        this@PlaybackService.stopPlayback()
      }
    }
    val sessionBuilder = MediaSession.Builder(this, sessionPlayer)
    if (launchIntent != null) {
      sessionBuilder.setSessionActivity(
        PendingIntent.getActivity(
          this,
          0,
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
    }
    mediaSession = sessionBuilder.build()
    // This service is started directly by the Tauri bridge rather than by a
    // MediaController binding. Register the session now so Media3 creates its
    // notification controller and promotes buffering/playing audio to a
    // foreground media service within Android's startup deadline.
    addSession(mediaSession)
    publishSleepTimer()
    scheduleSleepTimerTick()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY -> requestFromIntent(intent)?.let { playRequest(it, resetReconnects = true) }
      ACTION_PAUSE -> pausePlayback()
      ACTION_RESUME -> resumePlayback()
      ACTION_STOP -> stopPlayback()
      ACTION_SET_VOLUME -> setVolume(intent.getFloatExtra(EXTRA_VOLUME, 1f))
    }
    super.onStartCommand(intent, flags, startId)
    return START_NOT_STICKY
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

  private fun playRequest(request: PlayRequest, resetReconnects: Boolean) {
    if (expireSleepTimerIfNeeded()) return
    if (sleepTimer.shouldRejectPlay(request.sleepRevision)) {
      // A resolver that started before expiry must not resurrect playback.
      // A later explicit play captures the finished revision and is admitted.
      if (currentRequest == null) {
        AudioStateStore.stop(this)
      }
      return
    }
    cancelReconnect()
    if (resetReconnects) reconnectAttempts = 0
    currentRequest = request
    wantsPlayback = true
    desiredVolume = request.volume.coerceIn(0f, 1f)
    applyOutputVolume()
    player.setMediaItem(mediaItem(request))
    player.prepare()
    player.play()
    AudioStateStore.update(this) {
      it.copy(
        status = STATUS_BUFFERING,
        generation = request.generation,
        sourceUrl = request.sourceUrl,
        title = request.title,
        stationId = request.stationId,
        positionMs = 0,
        volume = desiredVolume,
        error = null,
        trackTitle = null,
        playableUrl = request.url,
        isHls = request.isHls,
      )
    }
  }

  private fun pausePlayback() {
    if (expireSleepTimerIfNeeded()) return
    cancelReconnect()
    wantsPlayback = false
    if (player.currentMediaItem != null) player.pause()
    AudioStateStore.update(this) {
      it.copy(
        status = if (it.requestOrNull() == null) STATUS_IDLE else STATUS_PAUSED,
        positionMs = playerPosition(),
        error = null,
      )
    }
  }

  private fun resumePlayback() {
    if (expireSleepTimerIfNeeded()) return
    val request = currentRequest ?: AudioStateStore.snapshot(this).requestOrNull()
    if (request == null) {
      AudioStateStore.stop(this)
      return
    }
    // Reopening a live source rejoins the broadcast instead of draining audio
    // that was buffered before the user paused it.
    playRequest(request.copy(volume = desiredVolume), resetReconnects = false)
  }

  private fun stopPlayback(cancelSleepTimer: Boolean = true) {
    if (cancelSleepTimer && sleepTimer.hasActiveTimer()) {
      sleepTimer.cancel()
      publishSleepTimer()
      cancelSleepTimerTick()
    }
    cancelReconnect()
    reconnectAttempts = 0
    currentRequest = null
    wantsPlayback = false
    player.stop()
    player.clearMediaItems()
    AudioStateStore.stop(this)
  }

  private fun setVolume(volume: Float) {
    if (expireSleepTimerIfNeeded()) return
    desiredVolume = volume.coerceIn(0f, 1f)
    applyOutputVolume()
    AudioStateStore.update(this) { it.copy(volume = desiredVolume) }
  }

  private fun setSleepTimer(minutes: Int): PlaybackSnapshot {
    expireSleepTimerIfNeeded()
    val status = AudioStateStore.snapshot(this).status
    if (currentRequest == null || status !in setOf(STATUS_PLAYING, STATUS_BUFFERING, STATUS_PAUSED)) {
      throw IllegalStateException("Start or pause Android audio before setting a sleep timer")
    }
    sleepTimer.start(minutes)
    publishSleepTimer()
    applyOutputVolume()
    scheduleSleepTimerTick()
    return actualSnapshot(checkExpiry = false)
  }

  private fun cancelSleepTimer(): PlaybackSnapshot {
    sleepTimer.cancel()
    publishSleepTimer()
    cancelSleepTimerTick()
    applyOutputVolume()
    return actualSnapshot(checkExpiry = false)
  }

  private fun expireSleepTimerIfNeeded(): Boolean {
    if (!sleepTimer.finishIfDue()) return false
    publishSleepTimer()
    cancelSleepTimerTick()
    // The final fade sample is silence. Keep the desired volume intact in the
    // snapshot so a later explicit play starts at the user's chosen volume.
    player.volume = 0f
    stopPlayback(cancelSleepTimer = false)
    return true
  }

  private fun applyOutputVolume() {
    player.volume = (desiredVolume * sleepTimer.fadeMultiplier()).coerceIn(0f, 1f)
  }

  private fun publishSleepTimer() {
    val snapshot = sleepTimer.snapshot()
    AudioStateStore.update(this) { it.copy(sleepTimer = snapshot, volume = desiredVolume) }
  }

  private fun scheduleSleepTimerTick() {
    cancelSleepTimerTick()
    val remaining = sleepTimer.snapshot().timer?.remainingMs ?: return
    // Handler delay uses uptime and therefore pauses in deep sleep. A bounded
    // delay makes the elapsed-realtime guard run within one second of the CPU
    // waking even when a paused source did not hold ExoPlayer's wake lock.
    val delay = when {
      remaining <= 0 -> 0
      remaining > SleepTimerController.FADE_DURATION_MS.toLong() ->
        minOf(TIMER_GUARD_TICK_MS, remaining)
      else -> minOf(FADE_TICK_MS, remaining)
    }
    handler.postDelayed(sleepTimerTick, delay)
  }

  private fun cancelSleepTimerTick() {
    handler.removeCallbacks(sleepTimerTick)
  }

  private fun mediaItem(request: PlayRequest): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(request.title)
      .setStation(request.title)
      .build()
    return MediaItem.Builder()
      .setUri(request.url)
      .setMediaId(request.sourceUrl)
      .setMediaMetadata(metadata)
      .apply {
        if (request.isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
      }
      .build()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    updateStateFromPlayer()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_ENDED && player.playWhenReady) {
      scheduleReconnect("The stream ended")
      return
    }
    updateStateFromPlayer()
  }

  override fun onPlayerError(error: PlaybackException) {
    scheduleReconnect(error.message ?: "The stream could not be played")
  }

  override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
    val request = currentRequest ?: return
    val title = mediaMetadata.title?.toString()?.trim().orEmpty()
    val artist = mediaMetadata.artist?.toString()?.trim().orEmpty()
    val track = when {
      title.isBlank() || title == request.title -> null
      artist.isNotBlank() && !title.startsWith(artist) -> "$artist - $title"
      else -> title
    }
    AudioStateStore.update(this) { it.copy(trackTitle = track) }
  }

  private fun updateStateFromPlayer() {
    if (expireSleepTimerIfNeeded()) return
    if (currentRequest == null || player.playerError != null || pendingReconnect != null) return
    val status = when {
      player.isPlaying -> STATUS_PLAYING
      player.playbackState == Player.STATE_BUFFERING && player.playWhenReady -> STATUS_BUFFERING
      !player.playWhenReady -> {
        wantsPlayback = false
        STATUS_PAUSED
      }
      else -> return
    }
    AudioStateStore.update(this) {
      it.copy(status = status, positionMs = playerPosition(), error = null)
    }
  }

  private fun scheduleReconnect(reason: String) {
    if (expireSleepTimerIfNeeded()) return
    val request = currentRequest ?: return
    if (!wantsPlayback || AudioStateStore.snapshot(this).status == STATUS_PAUSED) return
    cancelReconnect()
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      AudioStateStore.update(this) {
        it.copy(status = STATUS_ERROR, positionMs = playerPosition(), error = reason)
      }
      return
    }

    val attempt = reconnectAttempts++
    val expectedGeneration = request.generation
    AudioStateStore.update(this) {
      it.copy(status = STATUS_BUFFERING, positionMs = playerPosition(), error = null)
    }
    val retry = Runnable {
      pendingReconnect = null
      if (expireSleepTimerIfNeeded()) return@Runnable
      val state = AudioStateStore.snapshot(this)
      if (currentRequest?.generation != expectedGeneration ||
        state.status == STATUS_IDLE || state.status == STATUS_PAUSED
      ) {
        return@Runnable
      }
      player.prepare()
      player.play()
    }
    pendingReconnect = retry
    handler.postDelayed(retry, RECONNECT_DELAYS_MS[attempt])
  }

  private fun cancelReconnect() {
    pendingReconnect?.let(handler::removeCallbacks)
    pendingReconnect = null
  }

  private fun playerPosition(): Long = player.currentPosition.coerceAtLeast(0)

  private fun actualSnapshot(checkExpiry: Boolean = true): PlaybackSnapshot {
    if (checkExpiry && expireSleepTimerIfNeeded()) return AudioStateStore.snapshot(this)
    val stored = AudioStateStore.snapshot(this)
    if (currentRequest == null) return stored
    val status = when {
      player.playerError != null && stored.status == STATUS_ERROR -> STATUS_ERROR
      pendingReconnect != null -> STATUS_BUFFERING
      player.isPlaying -> STATUS_PLAYING
      player.playbackState == Player.STATE_BUFFERING && player.playWhenReady -> STATUS_BUFFERING
      else -> STATUS_PAUSED
    }
    return stored.copy(
      status = status,
      positionMs = playerPosition(),
      volume = desiredVolume,
      sleepTimer = sleepTimer.snapshot(),
    )
  }

  override fun onDestroy() {
    cancelSleepTimerTick()
    if (sleepTimer.hasActiveTimer()) {
      sleepTimer.cancel()
      publishSleepTimer()
    }
    cancelReconnect()
    val state = AudioStateStore.snapshot(this)
    if (state.status == STATUS_PLAYING || state.status == STATUS_BUFFERING) {
      AudioStateStore.update(this) {
        it.copy(status = STATUS_PAUSED, positionMs = playerPosition(), error = null)
      }
    }
    mediaSession.release()
    player.release()
    instance = null
    super.onDestroy()
  }

  companion object {
    const val ACTION_PLAY = "com.aerowave.audio.action.PLAY"
    const val ACTION_PAUSE = "com.aerowave.audio.action.PAUSE"
    const val ACTION_RESUME = "com.aerowave.audio.action.RESUME"
    const val ACTION_STOP = "com.aerowave.audio.action.STOP"
    const val ACTION_SET_VOLUME = "com.aerowave.audio.action.SET_VOLUME"

    const val EXTRA_VOLUME = "volume"
    private const val EXTRA_URL = "url"
    private const val EXTRA_SOURCE_URL = "sourceUrl"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_STATION_ID = "stationId"
    private const val EXTRA_GENERATION = "generation"
    private const val EXTRA_IS_HLS = "isHls"
    private const val EXTRA_SLEEP_REVISION = "sleepRevision"
    private const val EXTRA_HAS_SLEEP_REVISION = "hasSleepRevision"
    private const val EXTRA_HAS_STATION_ID = "hasStationId"
    private const val MAX_RECONNECT_ATTEMPTS = 4
    private const val TIMER_GUARD_TICK_MS = 1_000L
    private const val FADE_TICK_MS = 250L
    private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

    @Volatile
    private var instance: PlaybackService? = null

    fun isRunning(): Boolean = instance != null

    internal fun play(request: PlayRequest, callback: (PlaybackSnapshot) -> Unit): Boolean {
      val service = instance ?: return false
      service.handler.post {
        service.playRequest(request, resetReconnects = true)
        callback(service.actualSnapshot(checkExpiry = false))
      }
      return true
    }

    internal fun pause(context: Context, callback: (PlaybackSnapshot) -> Unit) {
      val service = instance
      if (service == null) {
        callback(AudioStateStore.snapshot(context))
        return
      }
      service.handler.post {
        service.pausePlayback()
        callback(service.actualSnapshot(checkExpiry = false))
      }
    }

    internal fun stop(context: Context, callback: (PlaybackSnapshot) -> Unit) {
      val service = instance
      if (service == null) {
        callback(AudioStateStore.stop(context))
        return
      }
      service.handler.post {
        service.stopPlayback()
        callback(AudioStateStore.snapshot(service))
      }
    }

    internal fun setVolume(context: Context, volume: Float, callback: (PlaybackSnapshot) -> Unit) {
      val service = instance
      if (service == null) {
        callback(AudioStateStore.update(context) { it.copy(volume = volume.coerceIn(0f, 1f)) })
        return
      }
      service.handler.post {
        service.setVolume(volume)
        callback(service.actualSnapshot(checkExpiry = false))
      }
    }

    internal fun snapshot(context: Context, callback: (PlaybackSnapshot) -> Unit) {
      val service = instance
      if (service == null) {
        callback(AudioStateStore.snapshot(context))
        return
      }
      service.handler.post { callback(service.actualSnapshot()) }
    }

    internal fun setSleepTimer(
      context: Context,
      minutes: Int,
      onSuccess: (PlaybackSnapshot) -> Unit,
      onError: (String) -> Unit,
    ) {
      val service = instance
      if (service == null) {
        onError("Start or pause Android audio before setting a sleep timer")
        return
      }
      service.handler.post {
        try {
          onSuccess(service.setSleepTimer(minutes))
        } catch (error: Exception) {
          onError(error.message ?: "Unable to set the sleep timer")
        }
      }
    }

    internal fun cancelSleepTimer(context: Context, callback: (PlaybackSnapshot) -> Unit) {
      val service = instance
      if (service == null) {
        callback(AudioStateStore.snapshot(context))
        return
      }
      service.handler.post { callback(service.cancelSleepTimer()) }
    }

    internal fun intentFor(
      context: Context,
      action: String,
      request: PlayRequest? = null,
    ): Intent = Intent(context, PlaybackService::class.java).setAction(action).apply {
      if (request != null) {
        putExtra(EXTRA_URL, request.url)
        putExtra(EXTRA_SOURCE_URL, request.sourceUrl)
        putExtra(EXTRA_TITLE, request.title)
        putExtra(EXTRA_HAS_STATION_ID, request.stationId != null)
        putExtra(EXTRA_STATION_ID, request.stationId)
        putExtra(EXTRA_VOLUME, request.volume)
        putExtra(EXTRA_GENERATION, request.generation)
        putExtra(EXTRA_IS_HLS, request.isHls)
        putExtra(EXTRA_HAS_SLEEP_REVISION, request.sleepRevision != null)
        request.sleepRevision?.let { putExtra(EXTRA_SLEEP_REVISION, it) }
      }
    }

    private fun requestFromIntent(intent: Intent): PlayRequest? {
      val url = intent.getStringExtra(EXTRA_URL) ?: return null
      val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: return null
      val title = intent.getStringExtra(EXTRA_TITLE) ?: return null
      return PlayRequest(
        url = url,
        sourceUrl = sourceUrl,
        title = title,
        stationId = if (intent.getBooleanExtra(EXTRA_HAS_STATION_ID, false)) {
          intent.getStringExtra(EXTRA_STATION_ID)
        } else {
          null
        },
        volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f),
        generation = intent.getLongExtra(EXTRA_GENERATION, 0),
        isHls = intent.getBooleanExtra(EXTRA_IS_HLS, false),
        sleepRevision = if (intent.getBooleanExtra(EXTRA_HAS_SLEEP_REVISION, false)) {
          intent.getLongExtra(EXTRA_SLEEP_REVISION, 0)
        } else {
          null
        },
      )
    }
  }
}
