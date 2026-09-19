package com.aerowave.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import android.os.UserManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.concurrent.Executors

class PlaybackService : MediaSessionService(), Player.Listener {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var player: ExoPlayer
  private lateinit var mediaSession: MediaSession
  private var currentRequest: PlayRequest? = null
  private var reconnectAttempts = 0
  private var pendingReconnect: Runnable? = null
  private var wantsPlayback = false
  private var desiredVolume = 1f
  private val folderExecutor = Executors.newSingleThreadExecutor()
  private var folderResolutionToken = 0L
  private var resolvingFolder = false
  private var folderPickFailures = 0
  private var alarmInterruption: AlarmInterruption? = null
  private lateinit var recoveryWakeLock: PowerManager.WakeLock
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
    recoveryWakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:playback-recovery")
      .apply { setReferenceCounted(false) }
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
    val networkDataSourceFactory = DataSource.Factory {
      val allowLoopback = currentRequest?.isHls == false
      OkHttpDataSource.Factory(NetworkGuard.client(allowLoopback, userAgent))
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        .createDataSource()
    }
    val dataSourceFactory = DefaultDataSource.Factory(this, networkDataSourceFactory)
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

  private fun playRequest(
    request: PlayRequest,
    resetReconnects: Boolean,
    resetFolderFailures: Boolean = true,
  ) {
    if (isAlarmActive()) return
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
    folderResolutionToken++
    resolvingFolder = false
    if (resetFolderFailures) folderPickFailures = 0
    if (resetReconnects) reconnectAttempts = 0
    currentRequest = request
    wantsPlayback = true
    desiredVolume = request.volume.coerceIn(0f, 1f)
    applyOutputVolume()
    player.setMediaItem(mediaItem(request))
    player.prepare()
    player.play()
    trace("play")
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
        sourceFolder = request.sourceFolder,
        backupFolder = request.backupFolder,
      )
    }
  }

  private fun pausePlayback() {
    alarmInterruption = alarmInterruption?.copy(resumeOnAutomaticEnd = false)
    if (expireSleepTimerIfNeeded()) return
    cancelReconnect()
    folderResolutionToken++
    resolvingFolder = false
    releaseRecoveryWakeLock()
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
    if (isAlarmActive()) return
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
    alarmInterruption = null
    if (cancelSleepTimer && sleepTimer.hasActiveTimer()) {
      sleepTimer.cancel()
      publishSleepTimer()
      cancelSleepTimerTick()
    }
    cancelReconnect()
    folderResolutionToken++
    resolvingFolder = false
    releaseRecoveryWakeLock()
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
    check(!isAlarmActive()) { "Dismiss or snooze the ringing alarm before setting a sleep timer" }
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
    if (isPlaying) {
      folderPickFailures = 0
      releaseRecoveryWakeLock()
    }
    updateStateFromPlayer()
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (!playWhenReady && reason in setOf(
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
      )
    ) {
      wantsPlayback = false
    }
    trace("pwr=$playWhenReady/$reason")
    updateStateFromPlayer()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    trace("state=$playbackState")
    if (playbackState == Player.STATE_READY && rejectSourceWithoutAudio()) return
    if (playbackState == Player.STATE_ENDED && wantsPlayback) {
      val request = currentRequest
      if (request?.sourceFolder != null) {
        advanceFolder(request.sourceFolder, request.url, "The folder has no playable tracks", false)
        return
      }
      scheduleReconnect("The stream ended")
      return
    }
    updateStateFromPlayer()
  }

  override fun onPlayerError(error: PlaybackException) {
    scheduleReconnect(error.message ?: "The stream could not be played")
  }

  override fun onTracksChanged(tracks: Tracks) {
    rejectSourceWithoutAudio()
  }

  private fun rejectSourceWithoutAudio(): Boolean {
    if (player.playbackState != Player.STATE_READY) return false
    val tracks = player.currentTracks
    if (tracks.isEmpty) return false
    val selectedAudio = tracks.groups.any { group ->
      group.type == C.TRACK_TYPE_AUDIO &&
        (0 until group.length).any { index ->
          group.isTrackSelected(index) && group.isTrackSupported(index)
        }
    }
    if (selectedAudio) return false
    scheduleReconnect("The selected source has no playable audio track")
    return true
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
    if (player.playbackState == Player.STATE_ENDED && wantsPlayback) return
    val status = when {
      player.isPlaying -> STATUS_PLAYING
      player.playbackState == Player.STATE_BUFFERING && player.playWhenReady -> STATUS_BUFFERING
      !player.playWhenReady -> STATUS_PAUSED
      else -> return
    }
    AudioStateStore.update(this) {
      it.copy(status = status, positionMs = playerPosition(), error = null)
    }
  }

  private fun scheduleReconnect(reason: String) {
    if (expireSleepTimerIfNeeded()) return
    val request = currentRequest ?: return
    if (!wantsPlayback) return
    cancelReconnect()
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      if (request.sourceFolder != null) {
        advanceFolder(request.sourceFolder, request.url, reason, true)
        return
      }
      if (request.backupFolder != null) {
        folderPickFailures = 0
        advanceFolder(request.backupFolder, null, reason, false)
        return
      }
      AudioStateStore.update(this) {
        it.copy(status = STATUS_ERROR, positionMs = playerPosition(), error = reason)
      }
      releaseRecoveryWakeLock()
      return
    }

    val attempt = reconnectAttempts++
    holdRecoveryWakeLock()
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
      // prepare() alone is a no-op after a live source reaches ENDED. Replace
      // the item so every bounded attempt opens a fresh relay connection and
      // can either recover, fail into the next attempt, or reach the backup.
      trace("retry=$reconnectAttempts")
      player.setMediaItem(mediaItem(request))
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

  private fun advanceFolder(
    folder: String,
    exclude: String?,
    failureReason: String,
    sourceFailed: Boolean,
  ) {
    val previous = currentRequest ?: return
    if (sourceFailed && ++folderPickFailures > MAX_FOLDER_FAILURES) {
      resolvingFolder = false
      AudioStateStore.update(this) {
        it.copy(status = STATUS_ERROR, positionMs = playerPosition(), error = failureReason)
      }
      releaseRecoveryWakeLock()
      return
    }
    val expectedGeneration = previous.generation
    val token = ++folderResolutionToken
    resolvingFolder = true
    trace("folder-resolve")
    holdRecoveryWakeLock()
    AudioStateStore.update(this) { it.copy(status = STATUS_BUFFERING, error = null) }
    folderExecutor.execute {
      val result = runCatching {
        DocumentLibrary.randomTrack(this, folder, exclude).also {
          require(DocumentLibrary.validatePlayable(this, android.net.Uri.parse(it.path))) {
            "The selected track is unavailable"
          }
        }
      }
      handler.post {
        if (token != folderResolutionToken || currentRequest?.generation != expectedGeneration || !wantsPlayback) {
          return@post
        }
        result.fold(
          onSuccess = { pick ->
            resolvingFolder = false
            trace("folder-picked")
            playRequest(
              previous.copy(
                url = pick.path,
                sourceUrl = pick.path,
                title = pick.name,
                isHls = false,
                sourceFolder = folder,
                stationId = null,
              ),
              resetReconnects = true,
              resetFolderFailures = false,
            )
          },
          onFailure = { error ->
            resolvingFolder = false
            trace("folder-failed")
            AudioStateStore.update(this) {
              it.copy(
                status = STATUS_ERROR,
                positionMs = playerPosition(),
                error = "$failureReason; ${error.message ?: "the backup folder is unavailable"}",
              )
            }
            releaseRecoveryWakeLock()
          },
        )
      }
    }
  }

  private fun holdRecoveryWakeLock() {
    // Each failed connection starts a new bounded wait. Refresh the timeout so
    // the CPU stays awake through the last retry and any following folder scan.
    if (recoveryWakeLock.isHeld) recoveryWakeLock.release()
    recoveryWakeLock.acquire(RECOVERY_WAKE_TIMEOUT_MS)
  }

  private fun releaseRecoveryWakeLock() {
    if (recoveryWakeLock.isHeld) recoveryWakeLock.release()
  }

  private fun playerPosition(): Long = player.currentPosition.coerceAtLeast(0)

  private fun trace(event: String) {
    if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    Log.d(
      "AerowavePlayback",
      "$event pwr=${player.playWhenReady} wants=$wantsPlayback " +
        "folder=${currentRequest?.sourceFolder != null} resolving=$resolvingFolder",
    )
  }

  private fun isAlarmActive(): Boolean =
    AlarmPlaybackService.isRinging() || AlarmStateStore.snapshot(this).ringing != null

  private fun interruptForAlarm(occurrenceId: String) {
    val existing = alarmInterruption
    if (existing != null) return
    val stored = AudioStateStore.snapshot(this)
    val request = currentRequest
    val resumeAfterAlarm = request != null &&
      wantsPlayback && stored.status in setOf(STATUS_PLAYING, STATUS_BUFFERING)
    alarmInterruption = AlarmInterruption(
      occurrenceId = occurrenceId,
      request = request,
      generation = request?.generation,
      positionMs = playerPosition(),
      resumeOnAutomaticEnd = resumeAfterAlarm,
    )
    if (sleepTimer.hasActiveTimer()) {
      sleepTimer.cancel()
      publishSleepTimer()
      cancelSleepTimerTick()
    }
    cancelReconnect()
    folderResolutionToken++
    resolvingFolder = false
    releaseRecoveryWakeLock()
    wantsPlayback = false
    if (player.currentMediaItem != null) player.pause()
    AudioStateStore.update(this) {
      it.copy(
        status = if (request == null) STATUS_IDLE else STATUS_PAUSED,
        positionMs = playerPosition(),
        error = null,
      )
    }
  }

  private fun finishAlarmInterruption(occurrenceId: String, resume: Boolean) {
    val interruption = alarmInterruption ?: run {
      if (!resume) stopPlayback()
      return
    }
    if (interruption.occurrenceId != occurrenceId) return
    alarmInterruption = null
    if (!resume) {
      stopPlayback()
      return
    }
    val request = interruption.request ?: return
    if (!interruption.resumeOnAutomaticEnd ||
      currentRequest?.generation != interruption.generation ||
      AudioStateStore.snapshot(this).generation != interruption.generation
    ) {
      return
    }
    cancelReconnect()
    folderResolutionToken++
    resolvingFolder = false
    reconnectAttempts = 0
    folderPickFailures = 0
    val restoredRequest = request.copy(volume = desiredVolume)
    currentRequest = restoredRequest
    wantsPlayback = true
    applyOutputVolume()
    val item = mediaItem(restoredRequest)
    if (restoredRequest.sourceFolder != null && interruption.positionMs > 0) {
      player.setMediaItem(item, interruption.positionMs)
    } else {
      player.setMediaItem(item)
    }
    player.prepare()
    player.play()
    AudioStateStore.update(this) {
      it.copy(
        status = STATUS_BUFFERING,
        generation = restoredRequest.generation,
        sourceUrl = restoredRequest.sourceUrl,
        title = restoredRequest.title,
        stationId = restoredRequest.stationId,
        positionMs = interruption.positionMs,
        volume = desiredVolume,
        error = null,
        trackTitle = null,
        playableUrl = restoredRequest.url,
        isHls = restoredRequest.isHls,
        sourceFolder = restoredRequest.sourceFolder,
        backupFolder = restoredRequest.backupFolder,
      )
    }
  }

  private fun actualSnapshot(checkExpiry: Boolean = true): PlaybackSnapshot {
    if (checkExpiry && expireSleepTimerIfNeeded()) return AudioStateStore.snapshot(this)
    val stored = AudioStateStore.snapshot(this)
    if (currentRequest == null) return stored
    val status = when {
      stored.status == STATUS_ERROR -> STATUS_ERROR
      resolvingFolder -> STATUS_BUFFERING
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
    releaseRecoveryWakeLock()
    folderExecutor.shutdownNow()
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
    private const val EXTRA_SOURCE_FOLDER = "sourceFolder"
    private const val EXTRA_BACKUP_FOLDER = "backupFolder"
    private const val MAX_RECONNECT_ATTEMPTS = 4
    private const val MAX_FOLDER_FAILURES = 3
    private const val TIMER_GUARD_TICK_MS = 1_000L
    private const val FADE_TICK_MS = 250L
    private const val RECOVERY_WAKE_TIMEOUT_MS = 45_000L
    private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

    @Volatile
    private var instance: PlaybackService? = null

    fun isRunning(): Boolean = instance != null

    internal fun interruptForAlarm(occurrenceId: String) {
      val service = instance ?: return
      if (Looper.myLooper() == Looper.getMainLooper()) {
        service.interruptForAlarm(occurrenceId)
      } else {
        service.handler.post { service.interruptForAlarm(occurrenceId) }
      }
    }

    internal fun finishAlarmInterruption(
      context: Context,
      occurrenceId: String,
      resume: Boolean,
    ) {
      val service = instance
      if (service == null) {
        // Alarm components are direct-boot aware, while ordinary playback
        // state intentionally remains in credential-encrypted storage.
        if (!resume && context.getSystemService(UserManager::class.java).isUserUnlocked) {
          AudioStateStore.stop(context)
        }
        return
      }
      val finish = { service.finishAlarmInterruption(occurrenceId, resume) }
      if (Looper.myLooper() == Looper.getMainLooper()) finish() else service.handler.post(finish)
    }

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
        putExtra(EXTRA_SOURCE_FOLDER, request.sourceFolder)
        putExtra(EXTRA_BACKUP_FOLDER, request.backupFolder)
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
        sourceFolder = intent.getStringExtra(EXTRA_SOURCE_FOLDER),
        backupFolder = intent.getStringExtra(EXTRA_BACKUP_FOLDER),
      )
    }
  }
}

private data class AlarmInterruption(
  val occurrenceId: String,
  val request: PlayRequest?,
  val generation: Long?,
  val positionMs: Long,
  val resumeOnAutomaticEnd: Boolean,
)
