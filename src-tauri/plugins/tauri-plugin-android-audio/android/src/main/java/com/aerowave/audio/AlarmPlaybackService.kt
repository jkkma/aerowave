package com.aerowave.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.Executors

class AlarmPlaybackService : Service(), Player.Listener, SensorEventListener {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var player: ExoPlayer
  private var ring: RingingRecord? = null
  private var directLaunchRequestedForOccurrence: String? = null
  private var desiredVolume = 0.8f
  private var activeFolder: String? = null
  private var currentUri: String? = null
  private var sourceKind = "tone"
  private var sourceIsHls = false
  private var sourceFailure: String? = null
  private var diagnostics: AlarmStartupDiagnostics? = null
  private val sourceProgress = AlarmPlaybackProgress(SystemClock::elapsedRealtime)
  private val fadeProgress = AlarmFadeProgress(SystemClock::elapsedRealtime)
  private var focusPaused = false
  private var focusGranted = false
  private var focusError: String? = null
  private var fallbackStarted = false
  private var userAgent = "Aerowave/0.0.0"
  private lateinit var ringWakeLock: PowerManager.WakeLock
  private lateinit var preparationWakeLock: PowerManager.WakeLock
  private val folderExecutor = Executors.newSingleThreadExecutor()
  private val preparationExecutor = Executors.newSingleThreadExecutor()
  private var sourceResolutionToken = 0L
  private var preparation: AlarmPreparation.Candidate? = null
  private var preparationUri: String? = null
  private var preparationIsHls = false
  private var preparationResolutionToken = 0L
  private var preparationAttempts = 0
  private var preparationRetryPending = false
  private var preparationStartedElapsedMs = 0L
  private var preparationRetainedFromSnooze = false
  private val preparationProgress = AlarmPrewarmProgress(SystemClock::elapsedRealtime)
  private lateinit var audioManager: AudioManager
  private var audioFocusRequest: AudioFocusRequest? = null
  private var focusMultiplier = 1f
  private var systemTone: SystemAlarmTone? = null
  internal var systemToneFactory: (Context, Uri) -> SystemAlarmTone? = ::openSystemAlarmTone
  private var toneWatchdogMisses = 0
  private lateinit var sensors: SensorManager
  private var shakeSensor: Sensor? = null
  private val shakeDetector = AlarmShakeDetector()
  private val gravity = FloatArray(3)

  private val fadeTick = object : Runnable {
    override fun run() {
      if (ring == null) return
      if (systemTone != null && sourceKind == "tone") {
        fadeProgress.sampleTone(
          runCatching { systemTone?.isPlaying() }.getOrDefault(false) == true,
          focusPaused,
        )
      } else {
        fadeProgress.samplePlayer(player.currentPosition, player.isPlaying, focusPaused)
      }
      val volume = currentOutputVolume()
      player.volume = volume
      if (!setSystemToneVolume(volume)) {
        failSystemTone("${sourceFailure ?: "The alarm source is unavailable"}; the system alarm sound could not play")
        return
      }
      if (!fadeProgress.isComplete()) handler.postDelayed(this, FADE_TICK_MS)
    }
  }
  private val toneWatchdog = object : Runnable {
    override fun run() {
      val output = systemTone ?: return
      if (ring == null || sourceKind != "tone" || focusPaused || !focusGranted) return
      val playing = runCatching { output.isPlaying() }.getOrDefault(false)
      if (playing) {
        toneWatchdogMisses = 0
        handler.postDelayed(this, WATCHDOG_TICK_MS)
      } else if (toneWatchdogMisses++ == 0) {
        if (!startSystemTonePlayback()) {
          failSystemTone("${sourceFailure ?: "The alarm source is unavailable"}; the system alarm sound could not play")
        }
      } else {
        failSystemTone("${sourceFailure ?: "The alarm source is unavailable"}; the system alarm sound stopped playing")
      }
    }
  }
  private val autoStop = Runnable {
    val current = ring ?: return@Runnable
    if (current.trigger != "test" && current.autoSnoozesUsed < current.alarm.autoSnoozes) {
      finishRing(current.occurrenceId, snooze = true, auto = true)
    } else {
      finishRing(current.occurrenceId, snooze = false, auto = true)
    }
  }
  private val progressWatchdog = object : Runnable {
    override fun run() {
      if (ring == null) return
      val positionMs = player.currentPosition
      val isPlaying = player.isPlaying
      val playWhenReady = player.playWhenReady
      val failure = sourceProgress.failureReason(
        positionMs,
        isPlaying,
        playWhenReady,
        sourceKind == "station",
      )
      diagnostics?.watchdog(
        positionMs, player.bufferedPosition, isPlaying, playWhenReady,
        player.isLoading, focusPaused, failure,
      )
      if (failure != null) {
        handleSourceFailure(failure)
        return
      }
      handler.postDelayed(this, WATCHDOG_TICK_MS)
    }
  }
  private val preparationWatchdog = object : Runnable {
    override fun run() {
      val candidate = preparation ?: return
      val nowMs = System.currentTimeMillis()
      // A wall-clock change must not leave muted playback and its network wake
      // lock running beyond one preparation window.
      if (SystemClock.elapsedRealtime() - preparationStartedElapsedMs > MAX_PREPARATION_WAKE_MS) {
        clearPreparation(stopPlayer = true, stopService = true)
        return
      }
      // Claim and service delivery are separate operations. Keep the prepared
      // decoder muted briefly across that boundary instead of discarding it.
      if (nowMs >= candidate.atMs) {
        if (!AlarmPreparation.eligibleDuringClaim(this@AlarmPlaybackService, candidate)) {
          clearPreparation(stopPlayer = true, stopService = true)
        } else {
          handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
        return
      }
      if (!AlarmPreparation.stillCurrent(this@AlarmPlaybackService, candidate)) {
        clearPreparation(stopPlayer = true, stopService = true)
        return
      }
      preparationProgress.sample(player.currentPosition, player.isPlaying)
      handler.postDelayed(this, WATCHDOG_TICK_MS)
    }
  }
  private val preparationRetry = Runnable {
    preparationRetryPending = false
    preparation?.let { candidate ->
      if (AlarmPreparation.stillCurrent(this, candidate)) startPreparationAttempt(candidate)
      else clearPreparation(stopPlayer = true, stopService = true)
    }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    diagnostics = if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      AlarmStartupDiagnostics(this)
    } else null
    audioManager = getSystemService(AudioManager::class.java)
    sensors = getSystemService(SensorManager::class.java)
    ringWakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:active-alarm")
      .apply { setReferenceCounted(false) }
    preparationWakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:preparing-alarm")
      .apply { setReferenceCounted(false) }
    createChannel()
    val version = try {
      packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }
    userAgent = "Aerowave/$version"
    val guardedClient = NetworkGuard.client(false, userAgent)
    val networkClient = diagnostics?.withHttpEvents(guardedClient) ?: guardedClient
    val networkFactory = OkHttpDataSource.Factory(networkClient)
      .setUserAgent(userAgent)
      .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
    // DefaultDataSource keeps SAF content URIs local while all HTTP still
    // passes through the same public-network guard used by radio playback.
    val dataSourceFactory = DefaultDataSource.Factory(
      this, diagnostics?.withTransferEvents(networkFactory) ?: networkFactory,
    )
    val extractors = diagnostics?.let { trace ->
      ChainedOpusExtractorsFactory(
        onExtractorProbe = trace::extractorProbe,
        onAacAlignment = trace::aacAlignment,
      )
    } ?: ChainedOpusExtractorsFactory()
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      // Media usage follows Android's active media route, including Bluetooth,
      // while the foreground notification keeps the alarm semantics.
      .setUsage(C.USAGE_MEDIA)
      .build()
    player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(this, extractors)
          .setDataSourceFactory(dataSourceFactory),
      )
      // The service owns a transient request so focus remains tied to the full
      // ring lifecycle rather than to an individual fallback source.
      .setAudioAttributes(audioAttributes, false)
      // A disconnected headset or Bluetooth route must fall back to the
      // handset instead of pausing an alarm with no automatic noisy-route resume.
      .setHandleAudioBecomingNoisy(false)
      .build()
      .also {
        it.setWakeMode(C.WAKE_MODE_NETWORK)
        it.addListener(this)
        diagnostics?.let { trace -> it.addAnalyticsListener(trace.analyticsListener()) }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PREPARE -> {
        // A late preparation broadcast may arrive after FIRE has claimed the
        // alarm. A live or durable ring keeps its restart path authoritative.
        val activeRing = ring ?: AlarmStateStore.snapshot(this).ringing
        if (activeRing != null) {
          if (ring == null) startRing(activeRing.occurrenceId)
          return START_STICKY
        }
        intent?.let { startPreparation(it, startId) }
        return START_NOT_STICKY
      }
      ACTION_RING -> startRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID))
      ACTION_SNOOZE -> finishRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID), true, false)
      ACTION_DISMISS -> finishRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID), false, false)
      else -> startRing(null)
    }
    return START_REDELIVER_INTENT
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startPreparation(intent: Intent, startId: Int) {
    if (ring != null) return
    val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
    val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
    val expectedAtMs = intent.getLongExtra(EXTRA_AT_MS, -1L)
    val snoozed = intent.getBooleanExtra(EXTRA_SNOOZED, false)
    val candidate = AlarmPreparation.candidate(
      this, alarmId, occurrenceId, expectedAtMs, snoozed,
    ) ?: run {
      if (preparation == null) stopSelf(startId)
      return
    }
    if (preparation == candidate) return
    if (preparation?.let { it.atMs <= candidate.atMs && AlarmPreparation.stillCurrent(this, it) } == true) {
      return
    }
    clearPreparation(stopPlayer = true, stopService = false)
    preparation = candidate
    preparationAttempts = 0
    preparationStartedElapsedMs = SystemClock.elapsedRealtime()
    preparationRetainedFromSnooze = false
    player.volume = 0f
    if (!preparationWakeLock.isHeld) preparationWakeLock.acquire(MAX_PREPARATION_WAKE_MS)
    startForeground(PREPARATION_NOTIFICATION_ID, preparationNotification(candidate))
    diagnostics?.beginPreparation(candidate.occurrenceId)
    startPreparationAttempt(candidate)
    handler.post(preparationWatchdog)
  }

  private fun startPreparationAttempt(candidate: AlarmPreparation.Candidate) {
    if (preparation != candidate || !AlarmPreparation.stillCurrent(this, candidate)) return
    preparationAttempts++
    preparationRetryPending = false
    val token = ++preparationResolutionToken
    val held = candidate.heldUri
    if (!held.isNullOrBlank()) {
      playPreparedUri(candidate, held, candidate.heldIsHls)
      return
    }
    preparationExecutor.execute {
      val result = runCatching { AlarmStreamResolver.resolve(candidate.stationUrl, userAgent) }
      handler.post {
        if (token != preparationResolutionToken || preparation != candidate || ring != null) return@post
        if (!AlarmPreparation.stillCurrent(this, candidate)) {
          clearPreparation(stopPlayer = true, stopService = true)
          return@post
        }
        result.fold(
          onSuccess = { playPreparedUri(candidate, it.url, it.isHls) },
          onFailure = { retryPreparation("resolve_failed") },
        )
      }
    }
  }

  private fun playPreparedUri(candidate: AlarmPreparation.Candidate, uri: String, isHls: Boolean) {
    if (preparation != candidate || ring != null) return
    preparationUri = uri
    preparationIsHls = isHls
    preparationProgress.reset()
    sourceKind = "station"
    sourceIsHls = isHls
    currentUri = uri
    diagnostics?.sourceStart("station", uri, isHls)
    player.volume = 0f
    player.repeatMode = Player.REPEAT_MODE_OFF
    val item = MediaItem.Builder()
      .setUri(uri)
      .setMediaId(uri)
      .setMediaMetadata(MediaMetadata.Builder().setTitle(candidate.stationName).build())
      .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
      .build()
    player.setMediaItem(item)
    player.prepare()
    player.play()
  }

  private fun retryPreparation(reason: String) {
    val candidate = preparation ?: return
    if (preparationRetryPending || preparationUri == null && reason != "resolve_failed") return
    diagnostics?.event("prewarm_retry", "reason=$reason attempt=$preparationAttempts")
    preparationUri = null
    preparationResolutionToken++
    player.volume = 0f
    player.stop()
    player.clearMediaItems()
    preparationProgress.reset()
    val delayMs = preparationAttempts * PREPARATION_RETRY_STEP_MS
    if (preparationAttempts >= MAX_PREPARATION_ATTEMPTS ||
      System.currentTimeMillis() + delayMs + AlarmPreparation.MIN_USEFUL_LEAD_MS >= candidate.atMs) return
    preparationRetryPending = true
    handler.postDelayed(preparationRetry, delayMs)
  }

  private fun clearPreparation(stopPlayer: Boolean, stopService: Boolean) {
    if (preparation == null) return
    preparation = null
    preparationUri = null
    preparationResolutionToken++
    preparationRetryPending = false
    preparationStartedElapsedMs = 0L
    preparationRetainedFromSnooze = false
    handler.removeCallbacks(preparationWatchdog)
    handler.removeCallbacks(preparationRetry)
    preparationProgress.reset()
    if (preparationWakeLock.isHeld) preparationWakeLock.release()
    if (stopPlayer) {
      player.volume = 0f
      player.stop()
      player.clearMediaItems()
      currentUri = null
    }
    if (stopService && ring == null) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      NotificationManagerCompat.from(this).cancel(PREPARATION_NOTIFICATION_ID)
      stopSelf()
    }
  }

  private fun preparedUriFor(current: RingingRecord): String? {
    val candidate = preparation ?: return null
    val uri = preparationUri ?: return null
    if (!AlarmPreparation.matchesClaim(this, candidate, current)) return null
    preparationProgress.sample(player.currentPosition, player.isPlaying)
    if (!preparationProgress.isReady(player.isPlaying) || !hasPlayableAudioTrack(player.currentTracks)) {
      return null
    }
    return uri
  }

  private fun startRing(expectedOccurrence: String?) {
    val current = AlarmStateStore.snapshot(this).ringing
    if (current == null || (expectedOccurrence != null && current.occurrenceId != expectedOccurrence)) {
      clearPending(expectedOccurrence)
      if (preparation == null) stopSelf()
      return
    }
    if (ring?.occurrenceId == current.occurrenceId) {
      clearPending(current.occurrenceId)
      return
    }
    val preparedUri = preparedUriFor(current)
    val preparedIsHls = preparationIsHls
    val preparedTitle = preparation?.stationName
    val retainedPreparation = preparationRetainedFromSnooze
    clearPreparation(stopPlayer = preparedUri == null, stopService = false)
    clearPending(current.occurrenceId)
    stopSystemTone()
    stopShakeListening()
    ring = current
    diagnostics?.startRing(
      current.occurrenceId, current.trigger, focusGranted,
      preserveActive = preparedUri != null,
    )
    sourceResolutionToken++
    fallbackStarted = false
    sourceKind = if (preparedUri == null) current.sourceKind else "station"
    sourceIsHls = if (preparedUri == null) current.sourceIsHls else preparedIsHls
    activeFolder = if (preparedUri == null) current.sourceFolder else null
    currentUri = preparedUri ?: current.sourceUri
    sourceFailure = if (preparedUri == null) current.note else null
    desiredVolume = current.alarm.volume
    fadeProgress.start(current.alarm.fadeSecs)
    // The decoder may already be running without focus; keep it strictly
    // silent until the due-time claim obtains focus.
    player.volume = 0f
    postInitialRingingNotification(current, preparedUri, preparedTitle, preparedIsHls)
    NotificationManagerCompat.from(this).cancel(PREPARATION_NOTIFICATION_ID)
    PlaybackService.interruptForAlarm(current.occurrenceId)
    val holdMs = if (current.alarm.autoStopMins > 0) {
      current.alarm.autoStopMins * 60_000L + 60_000L
    } else {
      MAX_RING_WAKE_MS
    }.coerceAtMost(MAX_RING_WAKE_MS)
    if (!ringWakeLock.isHeld) ringWakeLock.acquire(holdMs)
    if (current.trigger != "test") startShakeListening()
    requestAlarmFocus()
    handler.removeCallbacks(fadeTick)
    handler.removeCallbacks(autoStop)
    handler.removeCallbacks(progressWatchdog)
    handler.post(fadeTick)
    if (current.alarm.autoStopMins > 0) {
      val due = current.startedElapsedMs + current.alarm.autoStopMins * 60_000L
      handler.postDelayed(autoStop, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
      scheduleNativeAutoStop(current, due)
    }
    if (preparedUri == null) {
      resolvePrimary(current)
    } else {
      if (retainedPreparation) diagnostics?.sourceStart("station", preparedUri, preparedIsHls)
      diagnostics?.event("prewarm_adopted", "positionMs=${player.currentPosition}")
      sourceProgress.start(focusPaused, player.currentPosition)
      handler.post(progressWatchdog)
      if (!focusGranted || focusPaused) player.pause()
      else player.volume = currentOutputVolume()
    }
  }

  private fun resolvePrimary(current: RingingRecord) {
    // Fresh claims default to "tone" before their configured source resolves.
    // A persisted tone URI identifies a fallback already selected for this ring.
    if (current.sourceKind == "tone" && !current.sourceUri.isNullOrBlank()) {
      playTone(current.note ?: "The original alarm source is unavailable")
      return
    }
    if (!current.sourceUri.isNullOrBlank()) {
      playUri(
        current.sourceUri,
        current.title ?: "Alarm",
        current.sourceFolder,
        current.sourceKind,
        current.note,
        current.sourceIsHls,
      )
      return
    }
    when (val source = current.alarm.source) {
      is NativeAlarmSource.Folder -> playFolder(source.path, null, null)
      is NativeAlarmSource.Station -> {
        val station = AlarmStateStore.snapshot(this).stations.find { it.id == source.stationId }
        if (station == null) {
          tryBackup("The selected station is no longer saved")
          return
        }
        resolveStation(station)
      }
    }
  }

  private fun resolveStation(station: NativeStation) {
    val occurrence = ring?.occurrenceId ?: return
    val token = ++sourceResolutionToken
    val startedMs = diagnostics?.resolveStart(station.url)
    folderExecutor.execute {
      val result = runCatching { AlarmStreamResolver.resolve(station.url, userAgent) }
      handler.post {
        if (token != sourceResolutionToken || ring?.occurrenceId != occurrence) return@post
        result.fold(
          onSuccess = { resolved ->
            if (startedMs != null) diagnostics?.resolveResult(startedMs, resolved.url, resolved.isHls, null)
            playUri(resolved.url, station.name, null, "station", null, resolved.isHls)
          },
          onFailure = { error ->
            if (startedMs != null) diagnostics?.resolveResult(startedMs, null, null, error)
            tryBackup(error.message ?: "The selected station is unavailable")
          },
        )
      }
    }
  }

  private fun playFolder(folder: String, exclude: String?, note: String?) {
    val occurrence = ring?.occurrenceId ?: return
    val token = ++sourceResolutionToken
    folderExecutor.execute {
      val result = runCatching {
        DocumentLibrary.randomTrack(this, folder, exclude).also {
          require(DocumentLibrary.validatePlayable(this, Uri.parse(it.path))) {
            "The selected track is unavailable"
          }
        }
      }
      handler.post {
        if (token != sourceResolutionToken || ring?.occurrenceId != occurrence) return@post
        result.fold(
          onSuccess = { pick ->
            playUri(pick.path, pick.name, folder, if (note == null) "folder" else "backup", note)
          },
          onFailure = { error ->
            if (note == null) tryBackup(error.message ?: "The alarm folder is unavailable")
            else playTone("$note; ${error.message ?: "the backup folder is unavailable"}")
          },
        )
      }
    }
  }

  private fun tryBackup(reason: String) {
    val folder = AlarmStateStore.snapshot(this).backupFolder
    diagnostics?.event("backup_route", "folderConfigured=${!folder.isNullOrBlank()}")
    if (folder.isNullOrBlank()) playTone(reason) else playFolder(folder, null, reason)
  }

  private fun playTone(reason: String) {
    fadeProgress.sourceChanged()
    val uris = listOfNotNull(
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    ).distinct()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      val uri = uris.firstOrNull()
      if (uri == null) {
        failSystemTone("$reason; no system alarm tone is configured")
        return
      }
      // Ringtone cannot control its own volume or looping before API 28.
      // Keep Media3's per-alarm fade and repeat behavior on those versions.
      playUri(uri.toString(), "System alarm", null, "tone", reason)
      player.repeatMode = Player.REPEAT_MODE_ONE
      return
    }

    stopSystemTone()
    handler.removeCallbacks(progressWatchdog)
    player.stop()
    player.clearMediaItems()
    activeFolder = null
    currentUri = null
    sourceKind = "tone"
    sourceIsHls = false
    sourceFailure = reason
    for ((index, uri) in uris.withIndex()) {
      val output = runCatching { systemToneFactory(this, uri) }
        .onFailure { Log.e("AerowaveAlarmPlayback", "System tone could not open", it) }
        .getOrNull() ?: continue
      systemTone = output
      currentUri = uri.toString()
      diagnostics?.sourceStart("tone", uri.toString(), false)
      toneWatchdogMisses = 0
      if (setSystemToneVolume(currentOutputVolume()) &&
        (!focusGranted || focusPaused || startSystemTonePlayback())) {
        sourceFailure = listOfNotNull(
          reason,
          "Using the system notification sound".takeIf { index > 0 },
        ).joinToString("; ")
        val note = listOfNotNull(sourceFailure, focusError).joinToString("; ")
        updateRingingSource("tone", "System alarm", note, uri.toString(), null, false)
        handler.removeCallbacks(fadeTick)
        handler.post(fadeTick)
        return
      }
      stopSystemTone()
    }
    failSystemTone(
      if (uris.isEmpty()) "$reason; no system alarm tone is configured"
      else "$reason; the system alarm sound could not play",
    )
  }

  private fun currentOutputVolume(): Float {
    if (ring == null) return 0f
    return (desiredVolume * fadeProgress.multiplier() * focusMultiplier).coerceIn(0f, 1f)
  }

  private fun setSystemToneVolume(volume: Float): Boolean {
    val output = systemTone ?: return true
    return runCatching { output.setVolume(volume) }
      .onFailure { Log.e("AerowaveAlarmPlayback", "System tone volume failed", it) }
      .isSuccess
  }

  private fun startSystemTonePlayback(): Boolean {
    val output = systemTone ?: return false
    if (!setSystemToneVolume(currentOutputVolume())) return false
    val started = runCatching { if (!output.isPlaying()) output.play() }
      .onFailure { Log.e("AerowaveAlarmPlayback", "System tone playback failed", it) }
      .isSuccess
    if (started) {
      handler.removeCallbacks(toneWatchdog)
      handler.postDelayed(toneWatchdog, WATCHDOG_TICK_MS)
    }
    return started
  }

  private fun failSystemTone(reason: String) {
    fadeProgress.sourceChanged()
    stopSystemTone()
    currentUri = null
    activeFolder = null
    sourceKind = "tone"
    sourceIsHls = false
    updateRingingSource("tone", "System alarm", reason, null, null, false)
  }

  private fun stopSystemTone() {
    handler.removeCallbacks(toneWatchdog)
    systemTone?.let { runCatching { it.stop() } }
    systemTone = null
    toneWatchdogMisses = 0
  }

  private fun playUri(
    uri: String,
    title: String,
    folder: String?,
    kind: String,
    note: String?,
    isHls: Boolean = uri.substringBefore('?').endsWith(".m3u8", true),
  ) {
    stopSystemTone()
    fadeProgress.sourceChanged()
    activeFolder = folder
    currentUri = uri
    sourceKind = kind
    sourceIsHls = isHls
    sourceFailure = note
    diagnostics?.sourceStart(kind, uri, isHls)
    player.volume = currentOutputVolume()
    player.repeatMode = if (kind == "folder" || kind == "tone") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    sourceProgress.start(focusPaused)
    handler.removeCallbacks(progressWatchdog)
    handler.post(progressWatchdog)
    val item = MediaItem.Builder()
      .setUri(uri)
      .setMediaId(uri)
      .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
      .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
      .build()
    player.setMediaItem(item)
    player.prepare()
    if (focusGranted && !focusPaused) player.play()
    updateRingingSource(
      kind,
      title,
      listOfNotNull(note, focusError).joinToString("; ").takeIf { it.isNotBlank() },
      uri,
      folder,
      isHls,
    )
  }

  private fun updateRingingSource(
    kind: String,
    title: String?,
    note: String?,
    uri: String? = currentUri,
    folder: String? = activeFolder,
    isHls: Boolean = sourceIsHls,
    postNotification: Boolean = true,
  ) {
    val occurrence = ring?.occurrenceId ?: return
    val changed = AlarmStateStore.update(this) { old ->
      val active = old.ringing
      if (active?.occurrenceId != occurrence) old else old.copy(
        revision = old.revision + 1,
        ringing = active.copy(
          sourceKind = kind,
          title = title,
          note = note,
          sourceUri = uri,
          sourceFolder = folder,
          sourceIsHls = isHls,
        ),
      )
    }
    ring = changed.ringing
    ring?.takeIf { postNotification }?.let {
      NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(it))
    }
  }

  internal fun postInitialRingingNotification(
    current: RingingRecord,
    preparedUri: String?,
    preparedTitle: String?,
    preparedIsHls: Boolean,
  ) {
    ring = current
    if (preparedUri != null) {
      // The first full-screen notification must already describe the adopted
      // station. Reposting it immediately can launch the same activity twice.
      updateRingingSource(
        "station", preparedTitle ?: current.title ?: "Alarm", null,
        preparedUri, null, preparedIsHls, postNotification = false,
      )
    }
    startForeground(NOTIFICATION_ID, notification(ring ?: current))
    requestDndAlarmScreenIfNeeded(ring ?: current)
  }

  private fun requestDndAlarmScreenIfNeeded(current: RingingRecord) {
    val occurrence = current.occurrenceId
    if (directLaunchRequestedForOccurrence == occurrence ||
      ring?.occurrenceId != occurrence ||
      AlarmStateStore.snapshot(this).ringing?.occurrenceId != occurrence) return
    val eligible = runCatching {
      AlarmDnd.shouldRequestAlarmScreen(
        Build.MANUFACTURER, Build.BRAND, current.trigger,
        AlarmDnd.active(this),
        getSystemService(PowerManager::class.java).isInteractive,
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
          (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
              PackageManager.PERMISSION_GRANTED),
        alarmChannelImportance(this),
        canUseFullScreenIntent(),
        alarmScreenOverlayAccess(this) == "granted",
      )
    }.getOrElse { error ->
      Log.w("AerowaveAlarmUi", "DND alarm screen eligibility unavailable", error)
      false
    }
    if (!eligible) return
    if (ring?.occurrenceId != occurrence ||
      AlarmStateStore.snapshot(this).ringing?.occurrenceId != occurrence) return
    // MIUI can suppress the full-screen notification solely because DND is on.
    // A user-granted overlay capability permits background activity starts on
    // this Android version; no overlay window is created. Android still decides
    // whether the requested alarm activity actually becomes visible.
    directLaunchRequestedForOccurrence = occurrence
    try {
      startActivity(AlarmActivity.intentFor(this, occurrence))
      Log.i("AerowaveAlarmUi", "DND alarm screen launch requested; visibility unverified occurrence=$occurrence")
    } catch (error: Exception) {
      Log.w("AerowaveAlarmUi", "DND alarm screen launch rejected occurrence=$occurrence", error)
    }
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    diagnostics?.playerState(playbackState, player.isLoading, player.isPlaying)
    if (preparation != null && ring == null) {
      if (playbackState == Player.STATE_READY && !player.currentTracks.isEmpty &&
        !hasPlayableAudioTrack(player.currentTracks)) retryPreparation("no_audio_track")
      else if (playbackState == Player.STATE_ENDED && preparationUri != null) {
        retryPreparation("station_ended")
      }
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sourceKind == "tone") return
    if (playbackState == Player.STATE_READY && rejectSourceWithoutAudio()) return
    if (playbackState != Player.STATE_ENDED) return
    val folder = activeFolder
    if (sourceKind == "backup" && folder != null) {
      playFolder(folder, currentUri, sourceFailure)
    } else if (sourceKind == "station") {
      handleSourceFailure("The station stopped playing")
    }
  }

  override fun onPlayerError(error: PlaybackException) {
    diagnostics?.mediaError("player_error", error, "code=${error.errorCodeName}")
    if (preparation != null && ring == null) {
      Log.w("AerowaveAlarmPlayback", "Muted station preparation failed", error)
      retryPreparation("player_error")
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sourceKind == "tone") return
    Log.e("AerowaveAlarmPlayback", "Playback failed for $sourceKind source", error)
    val reason = when (sourceKind) {
      "station" -> "The selected station could not play"
      "folder" -> "The selected alarm track could not play"
      "backup" -> "The backup alarm track could not play"
      "tone" -> "The system alarm sound could not play"
      else -> "The alarm audio could not play"
    }
    handleSourceFailure(reason)
  }

  override fun onTracksChanged(tracks: Tracks) {
    diagnostics?.tracks(tracks)
    if (preparation != null && ring == null) {
      if (player.playbackState == Player.STATE_READY && !tracks.isEmpty &&
        !hasPlayableAudioTrack(tracks)) retryPreparation("no_audio_track")
      return
    }
    rejectSourceWithoutAudio()
  }

  override fun onIsLoadingChanged(isLoading: Boolean) {
    diagnostics?.playerLoading(isLoading)
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    diagnostics?.playerPlaying(isPlaying, player.currentPosition)
  }

  private fun rejectSourceWithoutAudio(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sourceKind == "tone") return false
    if (ring == null || player.playbackState != Player.STATE_READY) return false
    val tracks = player.currentTracks
    if (tracks.isEmpty) return false
    if (hasPlayableAudioTrack(tracks)) return false
    handleSourceFailure("The selected alarm source has no playable audio track")
    return true
  }

  private fun hasPlayableAudioTrack(tracks: Tracks): Boolean = tracks.groups.any { group ->
      group.type == C.TRACK_TYPE_AUDIO &&
        (0 until group.length).any { index ->
          group.isTrackSelected(index) && group.isTrackSupported(index)
        }
    }

  private fun handleSourceFailure(reason: String) {
    diagnostics?.failureDecision(reason, fallbackStarted, sourceKind)
    handler.removeCallbacks(progressWatchdog)
    fadeProgress.pause()
    player.stop()
    when (sourceKind) {
      "tone" -> updateRingingSource("tone", "System alarm", reason)
      "backup" -> playTone("${sourceFailure ?: reason}; $reason")
      else -> {
        if (fallbackStarted) playTone(reason)
        else {
          fallbackStarted = true
          tryBackup(reason)
        }
      }
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    val current = ring ?: return
    if (current.trigger == "test" || shakeSensor == null ||
      event.sensor.type != shakeSensor?.type) return
    // DeskClock's raw accelerometer filter retains abrupt motion while
    // removing gravity, regardless of the phone's orientation.
    for (index in 0..2) {
      gravity[index] = 0.8f * gravity[index] + 0.2f * event.values[index]
    }
    if (shakeDetector.sample(
        event.values[0] - gravity[0],
        event.values[1] - gravity[1],
        event.values[2] - gravity[2],
      ) &&
      AlarmStateStore.snapshot(this).ringing?.occurrenceId == current.occurrenceId) {
      stopShakeListening()
      finishRing(current.occurrenceId, snooze = true, auto = false)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun startShakeListening() {
    val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    gravity.fill(0f)
    shakeDetector.reset()
    if (sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)) {
      shakeSensor = sensor
    }
  }

  private fun stopShakeListening() {
    if (shakeSensor != null) sensors.unregisterListener(this)
    shakeSensor = null
    gravity.fill(0f)
    shakeDetector.reset()
  }

  private fun finishRing(
    occurrenceId: String?,
    snooze: Boolean,
    auto: Boolean,
  ): PersistedAlarmState? {
    val current = ring ?: AlarmStateStore.snapshot(this).ringing ?: return null
    if (occurrenceId != null && occurrenceId != current.occurrenceId) return null
    diagnostics?.finishRing(if (snooze) "snooze" else if (auto) "auto_dismiss" else "dismiss")
    stopShakeListening()
    handler.removeCallbacks(fadeTick)
    handler.removeCallbacks(autoStop)
    handler.removeCallbacks(progressWatchdog)
    sourceResolutionToken++
    val retainedUri = currentUri?.takeIf {
      snooze && current.trigger != "test" && sourceKind == "station" &&
        player.isPlaying && hasPlayableAudioTrack(player.currentTracks)
    }
    // Mute before releasing focus or changing any persisted alarm state.
    player.volume = 0f
    stopSystemTone()
    if (retainedUri == null) {
      player.stop()
      player.clearMediaItems()
    }
    abandonAlarmFocus()
    cancelNativeAutoStop(current)
    if (ringWakeLock.isHeld) ringWakeLock.release()
    val changed = if (snooze && current.trigger != "test") {
      AndroidAlarmScheduler.scheduleSnooze(
        this, current,
        if (auto) current.autoSnoozesUsed + 1 else current.autoSnoozesUsed,
      )
    } else {
      AndroidAlarmScheduler.dismiss(this, current.occurrenceId)
    }
    PlaybackService.finishAlarmInterruption(this, current.occurrenceId, resume = auto)
    clearPending(current.occurrenceId)
    ring = null
    val next = if (retainedUri == null) null else {
      AlarmPreparation.next(changed, System.currentTimeMillis())
        ?.takeIf { it.alarmId == current.alarm.id && it.snoozed &&
          it.heldUri == retainedUri && it.atMs - System.currentTimeMillis() <= 70_000L }
    }
    if (next != null && AlarmPreparation.stillCurrent(this, next)) {
      preparation = next
      preparationUri = retainedUri
      preparationIsHls = sourceIsHls
      preparationAttempts = 1
      preparationStartedElapsedMs = SystemClock.elapsedRealtime()
      preparationRetainedFromSnooze = true
      preparationProgress.reset(player.currentPosition)
      if (!preparationWakeLock.isHeld) preparationWakeLock.acquire(MAX_PREPARATION_WAKE_MS)
      startForeground(PREPARATION_NOTIFICATION_ID, preparationNotification(next))
      NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
      diagnostics?.beginPreparation(next.occurrenceId)
      diagnostics?.event("prewarm_retained", "positionMs=${player.currentPosition}")
      handler.post(preparationWatchdog)
    } else {
      if (retainedUri != null) {
        player.stop()
        player.clearMediaItems()
      }
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
    return changed
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val audio = PlatformAudioAttributes.Builder()
      .setUsage(PlatformAudioAttributes.USAGE_ALARM)
      .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
      .build()
    val channel = NotificationChannel(
      CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Aerowave alarms that are ringing"
      enableVibration(true)
      setSound(null, audio)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    val preparationChannel = NotificationChannel(
      PREPARATION_CHANNEL_ID, "Alarm preparation", NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "Aerowave prepares an upcoming station alarm"
      enableVibration(false)
      setSound(null, null)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    getSystemService(NotificationManager::class.java)
      .createNotificationChannels(listOf(channel, preparationChannel))
  }

  private fun preparationNotification(candidate: AlarmPreparation.Candidate): Notification =
    NotificationCompat.Builder(this, PREPARATION_CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle("Preparing alarm")
      .setContentText(candidate.stationName)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .build()

  private fun notification(current: RingingRecord): Notification {
    val launch = PendingIntent.getActivity(
      this, current.occurrenceId.hashCode() and 0x7fffffff,
      AlarmActivity.intentFor(this, current.occurrenceId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val snooze = AlarmActionReceiver.pendingIntent(this, ACTION_SNOOZE, current)
    val dismiss = AlarmActionReceiver.pendingIntent(this, ACTION_DISMISS, current)
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle(current.alarm.label.ifBlank { "Alarm" })
      .setContentText(current.title ?: "Aerowave is ringing")
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(launch)
      .addAction(0, "Dismiss", dismiss)
    if (current.trigger != "test") builder.addAction(0, "Snooze", snooze)
    if (canUseFullScreenIntent()) builder.setFullScreenIntent(launch, true)
    return builder.build()
  }

  private fun canUseFullScreenIntent(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
      getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    handler.post {
      if (ring == null) return@post
      diagnostics?.event("focus_change", "code=$change")
      when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> {
          focusPaused = false
          focusGranted = true
          focusError = null
          focusMultiplier = 1f
          if (systemTone != null && sourceKind == "tone") {
            toneWatchdogMisses = 0
            if (!startSystemTonePlayback()) {
              failSystemTone("${sourceFailure ?: "The alarm source is unavailable"}; the system alarm sound could not play")
              return@post
            }
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && sourceKind == "tone") {
            // A failed Ringtone must keep its error instead of reviving the old Media3 URI.
            return@post
          } else {
            sourceProgress.resumeFromFocus(player.currentPosition)
            if (ring != null && currentUri != null && !player.isPlaying) player.play()
          }
          ring?.let {
            updateRingingSource(
              sourceKind, it.title, sourceFailure, currentUri, activeFolder, sourceIsHls,
            )
          }
          handler.removeCallbacks(fadeTick)
          handler.post(fadeTick)
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          focusMultiplier = 0.2f
          handler.removeCallbacks(fadeTick)
          handler.post(fadeTick)
        }
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
          focusPaused = true
          fadeProgress.pause()
          if (systemTone != null && sourceKind == "tone") {
            handler.removeCallbacks(toneWatchdog)
            runCatching { systemTone?.stop() }
          } else {
            sourceProgress.pauseForFocus()
          }
          if (change == AudioManager.AUDIOFOCUS_LOSS) focusGranted = false
          player.pause()
        }
      }
    }
  }

  private fun requestAlarmFocus() {
    val attributes = PlatformAudioAttributes.Builder()
      .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
      .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
      .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(focusListener, handler)
        .build()
      audioFocusRequest = request
      applyFocusRequestResult(audioManager.requestAudioFocus(request))
    } else {
      @Suppress("DEPRECATION")
      applyFocusRequestResult(audioManager.requestAudioFocus(
        focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
      ))
    }
  }

  private fun applyFocusRequestResult(result: Int) {
    focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    focusPaused = !focusGranted
    focusError = when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> null
      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "Waiting for Android audio focus"
      else -> "Android denied audio focus; the alarm notification is still active"
    }
    diagnostics?.event("focus_request", "result=$result granted=$focusGranted paused=$focusPaused")
  }

  private fun abandonAlarmFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
      audioFocusRequest = null
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusListener)
    }
    focusGranted = false
    focusPaused = false
    focusError = null
    focusMultiplier = 1f
  }

  private fun scheduleNativeAutoStop(current: RingingRecord, atElapsedMs: Long) {
    if (!AndroidAlarmScheduler.canScheduleExact(this)) return
    try {
      getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        atElapsedMs,
        AlarmActionReceiver.autoStopPendingIntent(this, current),
      )
    } catch (_: SecurityException) {
      // The elapsed-time handler remains authoritative while the service runs.
    }
  }

  private fun cancelNativeAutoStop(current: RingingRecord) {
    val pending = AlarmActionReceiver.autoStopPendingIntent(this, current)
    getSystemService(AlarmManager::class.java).cancel(pending)
    pending.cancel()
  }

  override fun onDestroy() {
    diagnostics?.serviceDestroy()
    handler.removeCallbacksAndMessages(null)
    stopSystemTone()
    stopShakeListening()
    sourceResolutionToken++
    preparationResolutionToken++
    player.removeListener(this)
    player.release()
    abandonAlarmFocus()
    if (ringWakeLock.isHeld) ringWakeLock.release()
    if (preparationWakeLock.isHeld) preparationWakeLock.release()
    folderExecutor.shutdownNow()
    preparationExecutor.shutdownNow()
    instance = null
    super.onDestroy()
  }

  companion object {
    const val ACTION_PREPARE = "com.aerowave.audio.action.PREPARE_ALARM"
    const val ACTION_RING = "com.aerowave.audio.action.RING"
    const val ACTION_SNOOZE = "com.aerowave.audio.action.SNOOZE_ALARM"
    const val ACTION_DISMISS = "com.aerowave.audio.action.DISMISS_ALARM"
    private const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    private const val EXTRA_ALARM_ID = "alarmId"
    private const val EXTRA_AT_MS = "atMs"
    private const val EXTRA_SNOOZED = "snoozed"
    internal const val CHANNEL_ID = "aerowave_alarms_v1"
    private const val PREPARATION_CHANNEL_ID = "aerowave_alarm_preparation_v1"
    internal const val NOTIFICATION_ID = 71_001
    private const val PREPARATION_NOTIFICATION_ID = 71_002
    private const val FADE_TICK_MS = 250L
    private const val WATCHDOG_TICK_MS = 1_000L
    private const val MAX_RING_WAKE_MS = 2 * 60 * 60 * 1000L
    private const val MAX_PREPARATION_WAKE_MS = 75_000L
    private const val PREPARATION_RETRY_STEP_MS = 3_000L
    private const val MAX_PREPARATION_ATTEMPTS = 3

    @Volatile private var instance: AlarmPlaybackService? = null
    @Volatile private var pendingOccurrenceId: String? = null

    @JvmStatic
    fun isRinging(): Boolean = instance?.ring != null

    /** Hardware keys may act only on the occurrence owned by a live service. */
    @JvmStatic
    fun liveOccurrenceId(context: Context): String? {
      val active = instance?.ring?.occurrenceId ?: return null
      return active.takeIf { AlarmStateStore.snapshot(context).ringing?.occurrenceId == it }
    }

    @JvmStatic
    fun canShakeToSnooze(occurrenceId: String): Boolean =
      instance?.let { it.ring?.occurrenceId == occurrenceId && it.shakeSensor != null } == true

    @JvmStatic
    fun dismissIfMatching(context: Context, occurrenceId: String) {
      stopIfMatching(context, occurrenceId, snooze = false)
    }

    internal fun activeOccurrenceId(): String? =
      instance?.ring?.occurrenceId ?: pendingOccurrenceId

    internal fun markPending(occurrenceId: String) {
      pendingOccurrenceId = occurrenceId
    }

    internal fun clearPending(occurrenceId: String? = null) {
      if (occurrenceId == null || pendingOccurrenceId == occurrenceId) pendingOccurrenceId = null
    }

    fun intentFor(context: Context, action: String, occurrenceId: String): Intent =
      Intent(context, AlarmPlaybackService::class.java).setAction(action)
        .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)

    fun prepareIntentFor(
      context: Context,
      alarmId: String,
      occurrenceId: String,
      expectedAtMs: Long,
      snoozed: Boolean,
    ): Intent = Intent(context, AlarmPlaybackService::class.java)
      .setAction(ACTION_PREPARE)
      .putExtra(EXTRA_ALARM_ID, alarmId)
      .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
      .putExtra(EXTRA_AT_MS, expectedAtMs)
      .putExtra(EXTRA_SNOOZED, snoozed)

    internal fun revalidatePreparation(context: Context) {
      val service = instance ?: return
      service.handler.post {
        val candidate = service.preparation ?: return@post
        val valid = if (System.currentTimeMillis() >= candidate.atMs) {
          AlarmPreparation.eligibleDuringClaim(context, candidate)
        } else {
          AlarmPreparation.stillCurrent(context, candidate)
        }
        if (!valid) {
          service.clearPreparation(stopPlayer = true, stopService = true)
        }
      }
    }

    fun setVolume(volume: Float): Boolean {
      val service = instance ?: return false
      service.handler.post {
        service.desiredVolume = volume.coerceIn(0f, 1f)
        service.handler.removeCallbacks(service.fadeTick)
        service.handler.post(service.fadeTick)
      }
      return true
    }

    internal fun stopIfMatching(
      context: Context,
      occurrenceId: String,
      snooze: Boolean,
      callback: ((PersistedAlarmState) -> Unit)? = null,
    ) {
      val service = instance
      if (service != null) {
        service.handler.post {
          val changed = service.finishRing(occurrenceId, snooze, false)
            ?: AlarmStateStore.snapshot(context)
          callback?.invoke(changed)
        }
        return
      }
      val ring = AlarmStateStore.snapshot(context).ringing
      if (ring?.occurrenceId != occurrenceId) {
        callback?.invoke(AlarmStateStore.snapshot(context))
        return
      }
      if (snooze) AndroidAlarmScheduler.scheduleSnooze(context, ring)
      else AndroidAlarmScheduler.dismiss(context, occurrenceId)
      PlaybackService.finishAlarmInterruption(context, occurrenceId, resume = false)
      clearPending(occurrenceId)
      context.stopService(Intent(context, AlarmPlaybackService::class.java))
      NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
      callback?.invoke(AlarmStateStore.snapshot(context))
    }

    internal fun autoStopIfMatching(context: Context, occurrenceId: String) {
      val service = instance
      if (service != null) {
        service.handler.post {
          val current = service.ring ?: return@post
          if (current.occurrenceId != occurrenceId) return@post
          service.finishRing(
            occurrenceId,
            snooze = current.trigger != "test" &&
              current.autoSnoozesUsed < current.alarm.autoSnoozes,
            auto = true,
          )
        }
        return
      }
      val current = AlarmStateStore.snapshot(context).ringing ?: return
      if (current.occurrenceId != occurrenceId) return
      if (current.trigger != "test" && current.autoSnoozesUsed < current.alarm.autoSnoozes) {
        AndroidAlarmScheduler.scheduleSnooze(context, current, current.autoSnoozesUsed + 1)
      } else {
        AndroidAlarmScheduler.dismiss(context, occurrenceId)
      }
      PlaybackService.finishAlarmInterruption(context, occurrenceId, resume = true)
      clearPending(occurrenceId)
      NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    internal fun alarmChannelEnabled(context: Context): Boolean {
      if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
      return context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    internal fun alarmChannelImportance(context: Context): Int? =
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) null else
        context.getSystemService(NotificationManager::class.java)
          .getNotificationChannel(CHANNEL_ID)?.importance
  }
}

class AlarmActionReceiver : android.content.BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val occurrence = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      val active = AlarmStateStore.snapshot(context).ringing?.occurrenceId
      Log.d(
        "AerowaveAlarmAction",
        "received action=${intent.action} occurrence=$occurrence active=$active",
      )
    }
    when (intent.action) {
      AlarmPlaybackService.ACTION_SNOOZE -> AlarmPlaybackService.stopIfMatching(context, occurrence, true)
      AlarmPlaybackService.ACTION_DISMISS -> AlarmPlaybackService.stopIfMatching(context, occurrence, false)
      ACTION_AUTO_STOP -> AlarmPlaybackService.autoStopIfMatching(context, occurrence)
    }
  }

  companion object {
    private const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    private const val ACTION_AUTO_STOP = "com.aerowave.audio.action.AUTO_STOP_ALARM"

    internal fun pendingIntent(
      context: Context,
      action: String,
      ring: RingingRecord,
    ): PendingIntent {
      val intent = Intent(context, AlarmActionReceiver::class.java)
        .setAction(action)
        .setData(Uri.parse("aerowave://ring/${Uri.encode(ring.occurrenceId)}/${action.substringAfterLast('.') }"))
        .putExtra(EXTRA_OCCURRENCE_ID, ring.occurrenceId)
      val requestCode = (ring.occurrenceId + action).hashCode() and 0x7fffffff
      if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
        Log.d(
          "AerowaveAlarmAction",
          "pending action=$action occurrence=${ring.occurrenceId} request=$requestCode",
        )
      }
      return PendingIntent.getBroadcast(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    internal fun autoStopPendingIntent(context: Context, ring: RingingRecord): PendingIntent {
      val intent = Intent(context, AlarmActionReceiver::class.java)
        .setAction(ACTION_AUTO_STOP)
        .setData(Uri.parse("aerowave://ring/${Uri.encode(ring.occurrenceId)}/auto-stop"))
        .putExtra(EXTRA_OCCURRENCE_ID, ring.occurrenceId)
      return PendingIntent.getBroadcast(
        context, (ring.occurrenceId + ACTION_AUTO_STOP).hashCode() and 0x7fffffff, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}
