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
  private var foregroundActive = false
  private var latestDeliveredStartId = 0
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
  private val completionFade = AlarmCompletionFade(SystemClock::elapsedRealtime)
  private val completionCallbacks = mutableListOf<(PersistedAlarmState) -> Unit>()
  private var completionFocusMultiplier = 1f
  private var completionOutputCeiling = 0f
  private var lastCompletionLogMs = -1L
  private var automaticCompletionAttempted: String? = null
  private var toneOutputVolume = 0f
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
  private class ToneCandidates(val occurrenceId: String, val uris: List<Uri>, val reason: String) {
    var nextIndex = 0
  }
  private var toneCandidates: ToneCandidates? = null
  private var toneWatchdog: Runnable? = null
  private var toneWatchdogMisses = 0
  private lateinit var sensors: SensorManager
  private var shakeSensor: Sensor? = null
  private val shakeDetector = AlarmShakeDetector()
  private val gravity = FloatArray(3)
  private var loggedProgressPositionMs = 0L
  private var progressMilestoneLogged = false
  private var lastProgressLogElapsedMs = 0L
  private var lastVolumeLogElapsedMs = -1L
  private var fadeCompletionLogged = false
  private var lastFormatSignature: String? = null

  private val fadeTick = object : Runnable {
    override fun run() {
      if (ring == null) return
      if (completionFade.action != null) {
        fadeProgress.pause()
      } else if (systemTone != null && sourceKind == "tone") {
        fadeProgress.sampleTone(
          runCatching { systemTone?.isPlaying() }.getOrDefault(false) == true,
          focusPaused,
        )
      } else {
        fadeProgress.samplePlayer(player.currentPosition, player.isPlaying, focusPaused)
      }
      val volume = currentOutputVolume()
      player.volume = volume
      val complete = fadeProgress.isComplete()
      val now = SystemClock.elapsedRealtime()
      if (lastVolumeLogElapsedMs < 0 || (complete && !fadeCompletionLogged) ||
        now - lastVolumeLogElapsedMs >= 5_000L) {
        AlarmEventLog.record(this@AlarmPlaybackService, "volume.output", ring?.occurrenceId,
          mapOf("desiredVolume" to desiredVolume, "outputVolume" to volume,
            "fadeMultiplier" to fadeProgress.multiplier(), "focusMultiplier" to focusMultiplier,
            "fadeComplete" to complete, "sourceKind" to sourceKind))
        lastVolumeLogElapsedMs = now
        if (complete) fadeCompletionLogged = true
      }
      if (!setSystemToneVolume(volume)) {
        advanceToneCandidate("The system alarm sound could not play")
        return
      }
      if (!complete) handler.postDelayed(this, FADE_TICK_MS)
    }
  }
  private val completionTick = object : Runnable {
    override fun run() {
      val action = completionFade.action ?: return
      val state = AlarmStateStore.snapshot(this@AlarmPlaybackService)
      if (ring?.occurrenceId != action.occurrenceId || state.ringing?.occurrenceId != action.occurrenceId) {
        if (ring?.occurrenceId == action.occurrenceId) stopObsoleteRing()
        cancelCompletion(state)
        return
      }
      val volume = currentOutputVolume()
      player.volume = volume
      if (!setSystemToneVolume(volume)) {
        advanceToneCandidate("The system alarm sound could not play")
      }
      val elapsed = completionFade.elapsedMs()
      if (lastCompletionLogMs < 0 || elapsed - lastCompletionLogMs >= 1_000L || completionFade.isComplete()) {
        AlarmEventLog.record(this@AlarmPlaybackService, "ring.fade_out_progress", action.occurrenceId,
          mapOf("outputVolume" to volume, "elapsedMs" to elapsed, "durationMs" to completionFade.durationMs,
            "sourceKind" to sourceKind, "snooze" to action.snooze, "auto" to action.automatic))
        lastCompletionLogMs = elapsed
      }
      if (completionFade.isComplete()) completeRing(action)
      else handler.postDelayed(this, FADE_TICK_MS)
    }
  }
  private val autoStop = Runnable {
    val current = ring ?: return@Runnable
    if (current.trigger != "test" && current.autoSnoozesUsed < current.alarm.autoSnoozes) {
      finishRing(current.occurrenceId, snooze = true, auto = true, origin = "automatic_timeout")
    } else {
      finishRing(current.occurrenceId, snooze = false, auto = true, origin = "automatic_timeout")
    }
  }
  private val progressWatchdog = object : Runnable {
    override fun run() {
      if (ring == null) return
      val positionMs = player.currentPosition
      val isPlaying = player.isPlaying
      val playWhenReady = player.playWhenReady
      val nowElapsed = SystemClock.elapsedRealtime()
      if (positionMs + 250 < loggedProgressPositionMs) {
        AlarmEventLog.record(this@AlarmPlaybackService, "source.position_reset", ring?.occurrenceId,
          mapOf("previousPositionMs" to loggedProgressPositionMs, "positionMs" to positionMs,
            "sourceKind" to sourceKind, "isPlaying" to isPlaying))
      }
      if (isPlaying && positionMs > loggedProgressPositionMs + 50 &&
        (!progressMilestoneLogged || nowElapsed - lastProgressLogElapsedMs >= 30_000L)) {
        AlarmEventLog.record(this@AlarmPlaybackService, "source.progress", ring?.occurrenceId,
          mapOf("firstProgress" to !progressMilestoneLogged, "sourceKind" to sourceKind,
            "positionMs" to positionMs, "bufferedPositionMs" to player.bufferedPosition,
            "isPlaying" to isPlaying, "focusGranted" to focusGranted))
        progressMilestoneLogged = true
        lastProgressLogElapsedMs = nowElapsed
      }
      loggedProgressPositionMs = positionMs
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
        AlarmEventLog.record(this@AlarmPlaybackService, "prepare.invalidated", candidate.occurrenceId,
          mapOf("reason" to "elapsed_limit"))
        clearPreparation(stopPlayer = true, stopService = true)
        return
      }
      // Claim and service delivery are separate operations. Keep the prepared
      // decoder muted briefly across that boundary instead of discarding it.
      if (nowMs >= candidate.atMs) {
        if (!AlarmPreparation.eligibleDuringClaim(this@AlarmPlaybackService, candidate)) {
          AlarmEventLog.record(this@AlarmPlaybackService, "prepare.invalidated", candidate.occurrenceId,
            mapOf("reason" to "claim_no_longer_eligible"))
          clearPreparation(stopPlayer = true, stopService = true)
        } else {
          handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
        return
      }
      if (!AlarmPreparation.stillCurrent(this@AlarmPlaybackService, candidate)) {
        AlarmEventLog.record(this@AlarmPlaybackService, "prepare.invalidated", candidate.occurrenceId,
          mapOf("reason" to "identity_or_source_changed"))
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
    // Cold alarm delivery must satisfy Android's foreground deadline before
    // player construction or saved-state access can stall the main thread.
    createChannel()
    ensureForegroundStartup()
    AlarmEventLog.recordSystemContext(this, "service.create")
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
    latestDeliveredStartId = startId
    val occurrence = intent?.getStringExtra(EXTRA_OCCURRENCE_ID)
    AlarmEventLog.record(this, "service.start", occurrence,
      mapOf("startId" to startId, "flags" to flags, "action" to when (intent?.action) {
        ACTION_PREPARE -> "prepare"
        ACTION_RING -> "ring"
        ACTION_SNOOZE -> "snooze"
        ACTION_DISMISS -> "dismiss"
        null -> "restart"
        else -> "unknown"
      }))
    try {
      ensureForegroundStartup()
      val result = when (intent?.action) {
        ACTION_PREPARE -> {
          // A late preparation broadcast may arrive after FIRE has claimed the
          // alarm. A live or durable ring keeps its restart path authoritative.
          val activeRing = ring ?: AlarmStateStore.snapshot(this).ringing
          if (activeRing != null) {
            if (ring == null) startRing(activeRing.occurrenceId)
            START_STICKY
          } else {
            startPreparation(intent)
            START_NOT_STICKY
          }
        }
        else -> {
          when (intent?.action) {
            ACTION_RING -> startRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID))
            ACTION_SNOOZE -> finishRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID), true, false)
            ACTION_DISMISS -> finishRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID), false, false)
            else -> startRing(null)
          }
          START_REDELIVER_INTENT
        }
      }
      stopIfIdle()
      return result
    } catch (error: Throwable) {
      AlarmEventLog.record(this, "service.start_failed", occurrence,
        mapOf("startId" to startId), error)
      throw error
    } finally {
      AlarmServiceStarter.complete(intent)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun ensureForegroundStartup() {
    if (!foregroundActive) {
      promoteForeground(PREPARATION_NOTIFICATION_ID, preparationNotification())
    }
  }

  private fun promoteForeground(id: Int, notification: Notification) {
    try {
      startForeground(id, notification)
    } catch (error: Throwable) {
      AlarmEventLog.record(this, "service.foreground_failed", ring?.occurrenceId,
        mapOf("notificationId" to id), error)
      throw error
    }
    foregroundActive = true
    AlarmEventLog.record(this, "service.foreground", ring?.occurrenceId,
      mapOf("notificationId" to id))
  }

  private fun removeForeground() {
    AlarmEventLog.record(this, "service.foreground_removed", ring?.occurrenceId)
    foregroundActive = false
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  private fun stopIfIdle() {
    if (ring != null || preparation != null || !foregroundActive || latestDeliveredStartId <= 0) return
    // Android may have accepted a newer FIRE before delivering its start args.
    // A watchdog or dismissal must retain the foreground service for that work.
    val stopped = stopSelfResult(latestDeliveredStartId)
    AlarmEventLog.record(this, "service.idle_stop", fields =
      mapOf("startId" to latestDeliveredStartId, "stopped" to stopped))
    if (stopped) removeForeground()
  }

  private fun startPreparation(intent: Intent) {
    if (ring != null) {
      AlarmEventLog.record(this, "prepare.rejected", ring?.occurrenceId, mapOf("reason" to "ring_active"))
      return
    }
    val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
    val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID) ?: return
    val expectedAtMs = intent.getLongExtra(EXTRA_AT_MS, -1L)
    val snoozed = intent.getBooleanExtra(EXTRA_SNOOZED, false)
    val candidate = AlarmPreparation.candidate(
      this, alarmId, occurrenceId, expectedAtMs, snoozed,
    ) ?: run {
      AlarmEventLog.record(this, "prepare.rejected", occurrenceId,
        mapOf("reason" to "stale_or_ineligible", "expectedAtMs" to expectedAtMs))
      return
    }
    if (preparation == candidate) return
    if (preparation?.let { it.atMs <= candidate.atMs && AlarmPreparation.stillCurrent(this, it) } == true) {
      return
    }
    clearPreparation(stopPlayer = true, stopService = false)
    preparation = candidate
    AlarmEventLog.recordSystemContext(this, "prepare.start", candidate.occurrenceId,
      mapOf("atMs" to candidate.atMs, "leadMs" to candidate.atMs - System.currentTimeMillis(),
        "snoozed" to candidate.snoozed, "heldSource" to (candidate.heldUri != null)))
    preparationAttempts = 0
    preparationStartedElapsedMs = SystemClock.elapsedRealtime()
    preparationRetainedFromSnooze = false
    player.volume = 0f
    if (!preparationWakeLock.isHeld) preparationWakeLock.acquire(MAX_PREPARATION_WAKE_MS)
    promoteForeground(PREPARATION_NOTIFICATION_ID, preparationNotification(candidate))
    diagnostics?.beginPreparation(candidate.occurrenceId)
    startPreparationAttempt(candidate)
    handler.post(preparationWatchdog)
  }

  private fun startPreparationAttempt(candidate: AlarmPreparation.Candidate) {
    if (preparation != candidate || !AlarmPreparation.stillCurrent(this, candidate)) return
    preparationAttempts++
    preparationRetryPending = false
    val token = ++preparationResolutionToken
    AlarmEventLog.record(this, "prepare.attempt", candidate.occurrenceId,
      mapOf("attempt" to preparationAttempts, "heldSource" to (candidate.heldUri != null)))
    val held = candidate.heldUri
    if (!held.isNullOrBlank()) {
      playPreparedUri(candidate, held, candidate.heldIsHls)
      return
    }
    preparationExecutor.execute {
      val startedElapsed = SystemClock.elapsedRealtime()
      val result = runCatching { AlarmStreamResolver.resolve(candidate.stationUrl, userAgent) }
      handler.post {
        if (token != preparationResolutionToken || preparation != candidate || ring != null) return@post
        if (!AlarmPreparation.stillCurrent(this, candidate)) {
          clearPreparation(stopPlayer = true, stopService = true)
          return@post
        }
        result.fold(
          onSuccess = {
            AlarmEventLog.record(this, "prepare.resolved", candidate.occurrenceId,
              mapOf("durationMs" to SystemClock.elapsedRealtime() - startedElapsed, "isHls" to it.isHls))
            playPreparedUri(candidate, it.url, it.isHls)
          },
          onFailure = {
            AlarmEventLog.record(this, "prepare.resolve_failed", candidate.occurrenceId,
              mapOf("durationMs" to SystemClock.elapsedRealtime() - startedElapsed), it)
            retryPreparation("resolve_failed")
          },
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
    lastFormatSignature = null
    AlarmEventLog.record(this, "prepare.player_start", candidate.occurrenceId,
      mapOf("isHls" to isHls, "outputVolume" to 0f))
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
    AlarmEventLog.record(this, "prepare.retry", candidate.occurrenceId,
      mapOf("reason" to reason, "attempt" to preparationAttempts))
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
    AlarmEventLog.record(this, "prepare.clear", preparation?.occurrenceId,
      mapOf("stopPlayer" to stopPlayer, "stopService" to stopService))
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
    if (stopService) stopIfIdle()
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
      AlarmEventLog.record(this, "ring.rejected", expectedOccurrence,
        mapOf("reason" to if (current == null) "no_claim" else "occurrence_mismatch",
          "currentOccurrenceId" to current?.occurrenceId))
      clearPending(expectedOccurrence)
      return
    }
    if (ring?.occurrenceId == current.occurrenceId) {
      AlarmEventLog.record(this, "ring.duplicate_start", current.occurrenceId)
      clearPending(current.occurrenceId)
      return
    }
    cancelCompletion(AlarmStateStore.snapshot(this))
    automaticCompletionAttempted = null
    val preparedUri = preparedUriFor(current)
    val preparedIsHls = preparationIsHls
    val preparedTitle = preparation?.stationName
    val retainedPreparation = preparationRetainedFromSnooze
    clearPreparation(stopPlayer = preparedUri == null, stopService = false)
    clearPending(current.occurrenceId)
    stopSystemTone()
    stopShakeListening()
    ring = current
    AlarmEventLog.recordSystemContext(this, "ring.start", current.occurrenceId,
      mapOf("trigger" to current.trigger, "preparedAvailable" to (preparedUri != null),
        "claimToServiceMs" to System.currentTimeMillis() - current.startedAtMs,
        "autoStopMins" to current.alarm.autoStopMins, "autoSnoozesUsed" to current.autoSnoozesUsed,
        "volume" to current.alarm.volume, "fadeSecs" to current.alarm.fadeSecs))
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
    lastVolumeLogElapsedMs = -1L
    fadeCompletionLogged = false
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
    startShakeListening()
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
      AlarmEventLog.record(this, "prepare.adopted", current.occurrenceId,
        mapOf("positionMs" to player.currentPosition, "retainedFromSnooze" to retainedPreparation,
          "isHls" to preparedIsHls))
      sourceProgress.start(focusPaused, player.currentPosition)
      resetProgressLogging(player.currentPosition)
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
    AlarmEventLog.record(this, "source.resolve_start", occurrence, mapOf("sourceKind" to "station"))
    val token = ++sourceResolutionToken
    val startedMs = diagnostics?.resolveStart(station.url)
    folderExecutor.execute {
      val startedElapsed = SystemClock.elapsedRealtime()
      val result = runCatching { AlarmStreamResolver.resolve(station.url, userAgent) }
      handler.post {
        if (token != sourceResolutionToken || ring?.occurrenceId != occurrence) return@post
        result.fold(
          onSuccess = { resolved ->
            if (startedMs != null) diagnostics?.resolveResult(startedMs, resolved.url, resolved.isHls, null)
            AlarmEventLog.record(this, "source.resolved", occurrence,
              mapOf("durationMs" to SystemClock.elapsedRealtime() - startedElapsed, "isHls" to resolved.isHls))
            playUri(resolved.url, station.name, null, "station", null, resolved.isHls)
          },
          onFailure = { error ->
            if (startedMs != null) diagnostics?.resolveResult(startedMs, null, null, error)
            AlarmEventLog.record(this, "source.resolve_failed", occurrence,
              mapOf("durationMs" to SystemClock.elapsedRealtime() - startedElapsed), error)
            tryBackup(error.message ?: "The selected station is unavailable")
          },
        )
      }
    }
  }

  private fun playFolder(folder: String, exclude: String?, note: String?) {
    val occurrence = ring?.occurrenceId ?: return
    AlarmEventLog.record(this, "source.resolve_start", occurrence,
      mapOf("sourceKind" to if (note == null) "folder" else "backup", "excludePrevious" to (exclude != null)))
    val token = ++sourceResolutionToken
    folderExecutor.execute {
      val startedElapsed = SystemClock.elapsedRealtime()
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
            AlarmEventLog.record(this, "source.resolved", occurrence,
              mapOf("sourceKind" to if (note == null) "folder" else "backup",
                "durationMs" to SystemClock.elapsedRealtime() - startedElapsed))
            playUri(pick.path, pick.name, folder, if (note == null) "folder" else "backup", note)
          },
          onFailure = { error ->
            AlarmEventLog.record(this, "source.resolve_failed", occurrence,
              mapOf("sourceKind" to if (note == null) "folder" else "backup",
                "durationMs" to SystemClock.elapsedRealtime() - startedElapsed), error)
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
    // Fresh claims use a placeholder kind until a URI has resolved.
    val failedKind = if (currentUri == null) when (ring?.alarm?.source) {
      is NativeAlarmSource.Station -> "station"
      is NativeAlarmSource.Folder -> "folder"
      null -> sourceKind
    } else sourceKind
    AlarmEventLog.record(this, "source.fallback", ring?.occurrenceId,
      mapOf("fromKind" to failedKind, "toKind" to if (folder.isNullOrBlank()) "tone" else "backup",
        "reason" to sourceFailureCode(reason)))
    if (folder.isNullOrBlank()) playTone(reason) else playFolder(folder, null, reason)
  }

  private fun playTone(reason: String) {
    val current = ring ?: return
    val previousCandidates = toneCandidates?.takeIf { it.occurrenceId == current.occurrenceId }
    // The candidate budget belongs to the occurrence, including focus returns
    // and failures that arrive after its last candidate has been exhausted.
    if (previousCandidates != null && sourceKind == "tone") return
    AlarmEventLog.record(this, "source.tone_selected", ring?.occurrenceId,
      mapOf("reason" to sourceFailureCode(reason), "ringtoneApi" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)))
    resetProgressLogging()
    fadeProgress.sourceChanged()
    val uris = listOfNotNull(
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    ).distinct()
    stopSystemTone(clearCandidates = false)
    handler.removeCallbacks(progressWatchdog)
    player.stop()
    player.clearMediaItems()
    activeFolder = null
    currentUri = null
    sourceKind = "tone"
    sourceIsHls = false
    sourceFailure = reason
    toneCandidates = previousCandidates ?: ToneCandidates(current.occurrenceId, uris, reason)
    advanceToneCandidate("The system alarm sound could not play")
  }

  private fun toneCandidatesAreCurrent(candidates: ToneCandidates): Boolean =
    toneCandidates === candidates && ring?.occurrenceId == candidates.occurrenceId &&
      AlarmStateStore.snapshot(this).ringing?.occurrenceId == candidates.occurrenceId

  private fun advanceToneCandidate(failure: String) {
    val candidates = toneCandidates ?: return
    if (!toneCandidatesAreCurrent(candidates) || sourceKind != "tone") return
    if (currentUri != null) {
      AlarmEventLog.record(this, "source.tone_candidate_failed", candidates.occurrenceId,
        mapOf("candidateIndex" to candidates.nextIndex - 1, "reason" to sourceFailureCode(failure)))
    }
    stopSystemTone(clearCandidates = false)
    handler.removeCallbacks(progressWatchdog)
    fadeProgress.sourceChanged()
    player.stop()
    player.clearMediaItems()
    currentUri = null
    while (candidates.nextIndex < candidates.uris.size) {
      val index = candidates.nextIndex++
      val uri = candidates.uris[index]
      val note = listOfNotNull(
        candidates.reason,
        "Using the system notification sound".takeIf { index > 0 },
      ).joinToString("; ")
      resetProgressLogging()
      AlarmEventLog.record(this, "source.tone_candidate", candidates.occurrenceId,
        mapOf("candidateIndex" to index, "ringtoneApi" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)))
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        // Ringtone cannot control its volume or looping before API 28, but
        // decoder failures still need to consume the same ordered candidates.
        if (runCatching { playUri(uri.toString(), "System alarm", null, "tone", note) }
          .onFailure {
            AlarmEventLog.record(this, "source.tone_play_failed", candidates.occurrenceId,
              mapOf("candidateIndex" to index), it)
          }.isSuccess) return
        handler.removeCallbacks(progressWatchdog)
        player.stop()
        player.clearMediaItems()
        continue
      }
      val output = runCatching { systemToneFactory(this, uri) }
        .onFailure {
          Log.e("AerowaveAlarmPlayback", "System tone could not open", it)
          AlarmEventLog.record(this, "source.tone_open_failed", ring?.occurrenceId,
            mapOf("candidateIndex" to index), it)
        }
        .getOrNull() ?: continue
      if (!toneCandidatesAreCurrent(candidates)) {
        runCatching { output.stop() }
        return
      }
      systemTone = output
      currentUri = uri.toString()
      diagnostics?.sourceStart("tone", uri.toString(), false)
      toneWatchdogMisses = 0
      if (setSystemToneVolume(currentOutputVolume()) &&
        (!focusGranted || focusPaused || startSystemTonePlayback())) {
        sourceFailure = note
        updateRingingSource("tone", "System alarm", listOfNotNull(note, focusError).joinToString("; "),
          uri.toString(), null, false)
        handler.removeCallbacks(fadeTick)
        handler.post(fadeTick)
        return
      }
      stopSystemTone(clearCandidates = false)
    }
    failSystemTone(
      if (candidates.uris.isEmpty()) "${candidates.reason}; no system alarm tone is configured"
      else "${candidates.reason}; ${failure.replaceFirstChar { it.lowercase() }}",
    )
  }

  private fun currentOutputVolume(): Float {
    if (ring == null) return 0f
    if (completionFade.action != null) {
      if (focusPaused) {
        completionOutputCeiling = 0f
        return 0f
      }
      val focus = (focusMultiplier / completionFocusMultiplier).coerceIn(0f, 1f)
      // Unducking or replacing a stalled source must not make a finishing ring
      // louder than the gain it already reached during this envelope.
      completionOutputCeiling = minOf(completionOutputCeiling, completionFade.gain() * focus)
      return completionOutputCeiling.coerceIn(0f, 1f)
    }
    return (desiredVolume * fadeProgress.multiplier() * focusMultiplier).coerceIn(0f, 1f)
  }

  private fun setSystemToneVolume(volume: Float): Boolean {
    val output = systemTone ?: return true
    return runCatching { output.setVolume(volume) }
      .onSuccess { toneOutputVolume = volume }
      .onFailure {
        Log.e("AerowaveAlarmPlayback", "System tone volume failed", it)
        AlarmEventLog.record(this, "volume.tone_failed", ring?.occurrenceId, error = it)
      }
      .isSuccess
  }

  private fun startSystemTonePlayback(): Boolean {
    val output = systemTone ?: return false
    val candidates = toneCandidates ?: return false
    if (!toneCandidatesAreCurrent(candidates)) return false
    if (!setSystemToneVolume(currentOutputVolume())) return false
    val started = runCatching { if (!output.isPlaying()) output.play() }
      .onFailure {
        Log.e("AerowaveAlarmPlayback", "System tone playback failed", it)
        AlarmEventLog.record(this, "source.tone_play_failed", ring?.occurrenceId, error = it)
      }
      .isSuccess
    if (started) {
      AlarmEventLog.record(this, "source.tone_play_requested", ring?.occurrenceId,
        mapOf("focusGranted" to focusGranted, "outputVolume" to currentOutputVolume()))
      cancelToneWatchdog()
      val watchdog = object : Runnable {
        override fun run() {
          if (toneWatchdog !== this || systemTone !== output ||
            !toneCandidatesAreCurrent(candidates) || sourceKind != "tone" || focusPaused || !focusGranted) return
          val playing = runCatching { output.isPlaying() }.getOrDefault(false)
          if (playing) {
            if (!progressMilestoneLogged) {
              AlarmEventLog.record(this@AlarmPlaybackService, "source.progress", candidates.occurrenceId,
                mapOf("firstProgress" to true, "sourceKind" to "tone", "isPlaying" to true))
              progressMilestoneLogged = true
            }
            toneWatchdogMisses = 0
            handler.postDelayed(this, WATCHDOG_TICK_MS)
          } else if (toneWatchdogMisses++ == 0) {
            if (!startSystemTonePlayback()) advanceToneCandidate("The system alarm sound could not play")
          } else advanceToneCandidate("The system alarm sound stopped playing")
        }
      }
      toneWatchdog = watchdog
      handler.postDelayed(watchdog, WATCHDOG_TICK_MS)
    }
    return started
  }

  private fun failSystemTone(reason: String) {
    AlarmEventLog.record(this, "source.tone_failed", ring?.occurrenceId,
      mapOf("reason" to sourceFailureCode(reason)))
    fadeProgress.sourceChanged()
    stopSystemTone(clearCandidates = false)
    handler.removeCallbacks(progressWatchdog)
    player.stop()
    player.clearMediaItems()
    currentUri = null
    activeFolder = null
    sourceKind = "tone"
    sourceIsHls = false
    sourceFailure = reason
    updateRingingSource("tone", "System alarm", reason, null, null, false)
  }

  private fun cancelToneWatchdog() {
    toneWatchdog?.let { handler.removeCallbacks(it) }
    toneWatchdog = null
  }

  private fun stopSystemTone(clearCandidates: Boolean = true) {
    cancelToneWatchdog()
    systemTone?.let { runCatching { it.stop() } }
    systemTone = null
    toneWatchdogMisses = 0
    if (clearCandidates) toneCandidates = null
  }

  private fun playUri(
    uri: String,
    title: String,
    folder: String?,
    kind: String,
    note: String?,
    isHls: Boolean = uri.substringBefore('?').endsWith(".m3u8", true),
  ) {
    stopSystemTone(clearCandidates = kind != "tone")
    fadeProgress.sourceChanged()
    activeFolder = folder
    currentUri = uri
    sourceKind = kind
    sourceIsHls = isHls
    sourceFailure = note
    diagnostics?.sourceStart(kind, uri, isHls)
    AlarmEventLog.record(this, "source.player_start", ring?.occurrenceId,
      mapOf("sourceKind" to kind, "isHls" to isHls, "focusGranted" to focusGranted,
        "outputVolume" to currentOutputVolume()))
    lastFormatSignature = null
    player.volume = currentOutputVolume()
    player.repeatMode = if (kind == "folder" || kind == "tone") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    sourceProgress.start(focusPaused)
    resetProgressLogging()
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
    promoteForeground(NOTIFICATION_ID, notification(ring ?: current))
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
    AlarmEventLog.record(this, "screen.dnd_launch_requested", occurrence)
    if (ring?.occurrenceId != occurrence ||
      AlarmStateStore.snapshot(this).ringing?.occurrenceId != occurrence) return
    // MIUI can suppress the full-screen notification solely because DND is on.
    // A user-granted overlay capability permits background activity starts on
    // this Android version; no overlay window is created. Android still decides
    // whether the requested alarm activity actually becomes visible.
    directLaunchRequestedForOccurrence = occurrence
    try {
      startActivity(AlarmActivity.intentFor(this, occurrence))
      AlarmEventLog.record(this, "screen.dnd_launch_dispatched", occurrence)
      Log.i("AerowaveAlarmUi", "DND alarm screen launch requested; visibility unverified occurrence=$occurrence")
    } catch (error: Exception) {
      AlarmEventLog.record(this, "screen.dnd_launch_failed", occurrence, error = error)
      Log.w("AerowaveAlarmUi", "DND alarm screen launch rejected occurrence=$occurrence", error)
    }
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    diagnostics?.playerState(playbackState, player.isLoading, player.isPlaying)
    if (ring != null || preparation != null) AlarmEventLog.record(this, "source.player_state",
      ring?.occurrenceId ?: preparation?.occurrenceId,
      mapOf("state" to playbackState, "sourceKind" to sourceKind,
        "isLoading" to player.isLoading, "isPlaying" to player.isPlaying,
        "duringPreparation" to (ring == null)))
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
    } else if (sourceKind == "tone" && currentUri != null) {
      handleSourceFailure("The system alarm sound stopped playing")
    }
  }

  override fun onPlayerError(error: PlaybackException) {
    diagnostics?.mediaError("player_error", error, "code=${error.errorCodeName}")
    AlarmEventLog.record(this, "source.player_error", ring?.occurrenceId ?: preparation?.occurrenceId,
      mapOf("sourceKind" to sourceKind, "errorCode" to error.errorCode,
        "errorCodeName" to error.errorCodeName, "duringPreparation" to (ring == null)), error)
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
    logSelectedFormats(tracks)
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
    if (ring != null || preparation != null) AlarmEventLog.record(this, "source.playing_changed",
      ring?.occurrenceId ?: preparation?.occurrenceId,
      mapOf("sourceKind" to sourceKind, "isPlaying" to isPlaying,
        "positionMs" to player.currentPosition, "duringPreparation" to (ring == null)))
  }

  private fun logSelectedFormats(tracks: Tracks) {
    if (ring == null && preparation == null) return
    val formats = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
      (0 until group.length).filter { group.isTrackSelected(it) }.map { index ->
        val format = group.getTrackFormat(index)
        mapOf("mimeType" to format.sampleMimeType, "codecs" to format.codecs,
          "sampleRate" to format.sampleRate, "channelCount" to format.channelCount,
          "bitrate" to format.bitrate, "supported" to group.isTrackSupported(index))
      }
    }.take(3)
    val signature = formats.toString()
    if (signature == lastFormatSignature) return
    lastFormatSignature = signature
    formats.forEach { format ->
      AlarmEventLog.record(this, "source.format", ring?.occurrenceId ?: preparation?.occurrenceId,
        format + mapOf("sourceKind" to sourceKind, "duringPreparation" to (ring == null)))
    }
  }

  private fun resetProgressLogging(positionMs: Long = 0L) {
    loggedProgressPositionMs = positionMs
    progressMilestoneLogged = false
    lastProgressLogElapsedMs = 0L
  }

  private fun sourceFailureCode(reason: String): String = when {
    reason.contains("focus", true) -> "audio_focus"
    reason.contains("no playable audio", true) -> "no_audio_track"
    reason.contains("stopped", true) -> "playback_stopped"
    reason.contains("did not produce", true) -> "startup_no_progress"
    reason.contains("unavailable", true) -> "unavailable"
    reason.contains("HTTP", true) -> "http_error"
    reason.contains("too long", true) -> "timeout"
    else -> "source_error"
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
    if (ring == null) return
    diagnostics?.failureDecision(reason, fallbackStarted, sourceKind)
    AlarmEventLog.record(this, "source.failure", ring?.occurrenceId,
      mapOf("reason" to sourceFailureCode(reason), "sourceKind" to sourceKind,
        "fallbackStarted" to fallbackStarted, "positionMs" to player.currentPosition))
    handler.removeCallbacks(progressWatchdog)
    fadeProgress.pause()
    player.stop()
    when (sourceKind) {
      "tone" -> if (currentUri != null) advanceToneCandidate(reason)
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
    if (shakeSensor == null ||
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
      finishRing(current.occurrenceId, snooze = false, auto = false, origin = "shake")
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
    AlarmEventLog.record(this, "input.shake_listener", ring?.occurrenceId,
      mapOf("registered" to (shakeSensor != null)))
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
    origin: String = "service_intent",
    callback: ((PersistedAlarmState) -> Unit)? = null,
  ) {
    val state = AlarmStateStore.snapshot(this)
    val current = ring ?: AlarmStateStore.snapshot(this).ringing ?: run {
      AlarmEventLog.record(this, "ring.finish_rejected", occurrenceId,
        mapOf("reason" to "no_active_ring", "origin" to origin, "snooze" to snooze, "auto" to auto))
      callback?.invoke(state)
      return
    }
    if (occurrenceId != null && occurrenceId != current.occurrenceId) {
      AlarmEventLog.record(this, "ring.finish_rejected", occurrenceId,
        mapOf("reason" to "occurrence_mismatch", "origin" to origin,
          "currentOccurrenceId" to current.occurrenceId, "snooze" to snooze, "auto" to auto))
      callback?.invoke(state)
      return
    }
    if (state.ringing?.occurrenceId != current.occurrenceId) {
      stopObsoleteRing()
      cancelCompletion(state)
      callback?.invoke(state)
      return
    }
    if (auto && automaticCompletionAttempted == current.occurrenceId && completionFade.action == null) return
    if (auto) automaticCompletionAttempted = current.occurrenceId
    callback?.let(completionCallbacks::add)
    val tone = systemTone
    val audible = ring != null && focusGranted && !focusPaused &&
      (if (tone != null && sourceKind == "tone") runCatching { tone.isPlaying() }.getOrDefault(false)
        else player.isPlaying)
    val output = if (completionFade.action != null) currentOutputVolume()
      else minOf(currentOutputVolume(), if (tone != null && sourceKind == "tone") toneOutputVolume else player.volume)
    val action = AlarmCompletionFade.Action(current.occurrenceId, snooze && current.trigger != "test", auto, origin)
    val decision = completionFade.request(action, output, audible)
    if (decision != AlarmCompletionFade.Decision.STARTED) return
    ring = current
    completionFocusMultiplier = focusMultiplier.coerceAtLeast(0.001f)
    completionOutputCeiling = output
    lastCompletionLogMs = -1L
    fadeProgress.pause()
    AlarmEventLog.record(this, "ring.finish_requested", occurrenceId ?: current.occurrenceId,
      mapOf("origin" to origin, "snooze" to snooze, "auto" to auto,
        "currentOccurrenceId" to current.occurrenceId, "sourceKind" to sourceKind))
    AlarmEventLog.record(this, "ring.fade_out_started", current.occurrenceId,
      mapOf("outputVolume" to output, "durationMs" to completionFade.durationMs,
        "sourceKind" to sourceKind, "snooze" to action.snooze, "auto" to auto, "origin" to origin))
    handler.removeCallbacks(completionTick)
    // A cold action or already silent source has no audible envelope to wait for.
    if (completionFade.isComplete()) completeRing(action) else handler.post(completionTick)
  }

  private fun completeRing(action: AlarmCompletionFade.Action) {
    val current = ring ?: return
    if (completionFade.action != action || current.occurrenceId != action.occurrenceId) return
    val snooze = action.snooze
    val auto = action.automatic
    val origin = action.origin
    val changed = try {
      if (snooze) AndroidAlarmScheduler.scheduleSnooze(
        this, current, if (auto) current.autoSnoozesUsed + 1 else current.autoSnoozesUsed,
      ) else AndroidAlarmScheduler.dismiss(this, current.occurrenceId)
    } catch (error: Exception) {
      AlarmEventLog.record(this, "ring.finish_failed", current.occurrenceId,
        mapOf("origin" to origin, "snooze" to snooze, "auto" to auto), error)
      val restored = AlarmStateStore.snapshot(this)
      cancelCompletion(restored)
      // The player, focus, watchdog and CPU hold are still owned by this ring.
      // A failed save/arm restores gain and leaves every manual control retryable.
      if (restored.ringing?.occurrenceId == current.occurrenceId) {
        player.volume = currentOutputVolume()
        setSystemToneVolume(currentOutputVolume())
        handler.removeCallbacks(fadeTick)
        handler.post(fadeTick)
        AlarmEventLog.record(this, "ring.finish_recovered", current.occurrenceId,
          mapOf("outputVolume" to currentOutputVolume(), "sourceKind" to sourceKind))
      } else stopObsoleteRing()
      return
    }
    if (changed.ringing?.occurrenceId == current.occurrenceId) {
      cancelCompletion(changed)
      player.volume = currentOutputVolume()
      setSystemToneVolume(currentOutputVolume())
      return
    }
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
    // Completion has been accepted; mute before releasing focus.
    player.volume = 0f
    stopSystemTone()
    if (retainedUri == null) {
      player.stop()
      player.clearMediaItems()
    }
    abandonAlarmFocus()
    runCatching { cancelNativeAutoStop(current) }
    if (ringWakeLock.isHeld) ringWakeLock.release()
    AlarmEventLog.record(this, "ring.finish_committed", current.occurrenceId,
      mapOf("origin" to origin, "snooze" to snooze, "auto" to auto,
        "ringStillActive" to (changed.ringing?.occurrenceId == current.occurrenceId),
        "snoozePending" to changed.snoozes.containsKey(current.alarm.id)))
    runCatching { PlaybackService.finishAlarmInterruption(this, current.occurrenceId, resume = auto) }
    clearPending(current.occurrenceId)
    ring = null
    cancelCompletion(changed)
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
      promoteForeground(PREPARATION_NOTIFICATION_ID, preparationNotification(next))
      NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
      diagnostics?.beginPreparation(next.occurrenceId)
      diagnostics?.event("prewarm_retained", "positionMs=${player.currentPosition}")
      AlarmEventLog.record(this, "prepare.retained_snooze", next.occurrenceId,
        mapOf("positionMs" to player.currentPosition, "atMs" to next.atMs))
      handler.post(preparationWatchdog)
    } else {
      if (retainedUri != null) {
        player.stop()
        player.clearMediaItems()
      }
      stopIfIdle()
    }
  }

  private fun cancelCompletion(state: PersistedAlarmState) {
    handler.removeCallbacks(completionTick)
    completionFade.clear()
    val callbacks = completionCallbacks.toList()
    completionCallbacks.clear()
    callbacks.forEach { callback -> runCatching { callback(state) } }
  }

  private fun stopObsoleteRing() {
    val previous = ring
    handler.removeCallbacks(fadeTick)
    handler.removeCallbacks(autoStop)
    handler.removeCallbacks(progressWatchdog)
    sourceResolutionToken++
    stopShakeListening()
    player.volume = 0f
    stopSystemTone()
    player.stop()
    player.clearMediaItems()
    abandonAlarmFocus()
    previous?.let { runCatching { cancelNativeAutoStop(it) } }
    if (ringWakeLock.isHeld) ringWakeLock.release()
    ring = null
    stopIfIdle()
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

  private fun preparationNotification(candidate: AlarmPreparation.Candidate? = null): Notification =
    NotificationCompat.Builder(this, PREPARATION_CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle("Preparing alarm")
      .setContentText(candidate?.stationName)
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
      AlarmEventLog.recordSystemContext(this, "focus.change", ring?.occurrenceId,
        mapOf("change" to change, "sourceKind" to sourceKind,
          "focusGrantedBefore" to focusGranted, "focusPausedBefore" to focusPaused))
      when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> {
          focusPaused = false
          focusGranted = true
          focusError = null
          focusMultiplier = 1f
          if (systemTone != null && sourceKind == "tone") {
            toneWatchdogMisses = 0
            if (!startSystemTonePlayback()) {
              advanceToneCandidate("The system alarm sound could not play")
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
            cancelToneWatchdog()
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
    AlarmEventLog.recordSystemContext(this, "focus.request_result", ring?.occurrenceId,
      mapOf("result" to result, "focusGranted" to focusGranted, "focusPaused" to focusPaused))
  }

  private fun abandonAlarmFocus() {
    AlarmEventLog.record(this, "focus.abandon", ring?.occurrenceId)
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
      AlarmEventLog.record(this, "ring.auto_stop_scheduled", current.occurrenceId,
        mapOf("elapsedDeadlineMs" to atElapsedMs))
    } catch (error: SecurityException) {
      AlarmEventLog.record(this, "ring.auto_stop_schedule_failed", current.occurrenceId, error = error)
      // The elapsed-time handler remains authoritative while the service runs.
    }
  }

  private fun cancelNativeAutoStop(current: RingingRecord) {
    val pending = AlarmActionReceiver.autoStopPendingIntent(this, current)
    getSystemService(AlarmManager::class.java).cancel(pending)
    pending.cancel()
  }

  override fun onDestroy() {
    AlarmEventLog.record(this, "service.destroy", ring?.occurrenceId ?: preparation?.occurrenceId,
      mapOf("ringActive" to (ring != null), "preparationActive" to (preparation != null)))
    removeForeground()
    diagnostics?.serviceDestroy()
    handler.removeCallbacksAndMessages(null)
    cancelCompletion(AlarmStateStore.snapshot(this))
    stopSystemTone()
    stopShakeListening()
    sourceResolutionToken++
    preparationResolutionToken++
    if (::player.isInitialized) {
      player.removeListener(this)
      player.release()
    }
    if (::audioManager.isInitialized) abandonAlarmFocus()
    if (::ringWakeLock.isInitialized && ringWakeLock.isHeld) ringWakeLock.release()
    if (::preparationWakeLock.isInitialized && preparationWakeLock.isHeld) preparationWakeLock.release()
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
    fun canShakeToDismiss(occurrenceId: String): Boolean =
      instance?.let { it.ring?.occurrenceId == occurrenceId && it.shakeSensor != null } == true

    @JvmStatic
    fun volumeButtonIfMatching(context: Context, occurrenceId: String, onFailure: (() -> Unit)? = null) {
      val current = AlarmStateStore.snapshot(context).ringing
      if (current?.occurrenceId != occurrenceId || liveOccurrenceId(context) != occurrenceId) {
        AlarmEventLog.record(context, "input.volume_rejected", occurrenceId,
          mapOf("reason" to "no_matching_live_ring", "currentOccurrenceId" to current?.occurrenceId))
        onFailure?.invoke()
        return
      }
      stopIfMatching(context, occurrenceId, snooze = alarmVolumeButtonSnoozes(current.trigger), origin = "volume_button") { state ->
        if (state.ringing?.occurrenceId == occurrenceId) onFailure?.invoke()
      }
    }

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
        AlarmEventLog.record(context = service, event = "volume.requested", occurrenceId = service.ring?.occurrenceId,
          fields = mapOf("desiredVolume" to service.desiredVolume, "origin" to "webview"))
        service.handler.removeCallbacks(service.fadeTick)
        service.handler.post(service.fadeTick)
      }
      return true
    }

    internal fun stopIfMatching(
      context: Context,
      occurrenceId: String,
      snooze: Boolean,
      origin: String = "webview",
      callback: ((PersistedAlarmState) -> Unit)? = null,
    ) {
      val service = instance
      if (service != null) {
        service.handler.post {
          service.finishRing(occurrenceId, snooze, false, origin, callback)
        }
        return
      }
      val ring = AlarmStateStore.snapshot(context).ringing
      if (ring?.occurrenceId != occurrenceId) {
        AlarmEventLog.record(context, "ring.finish_rejected", occurrenceId,
          mapOf("reason" to "no_matching_durable_ring", "currentOccurrenceId" to ring?.occurrenceId,
            "origin" to origin, "snooze" to snooze))
        callback?.invoke(AlarmStateStore.snapshot(context))
        return
      }
      AlarmEventLog.record(context, "ring.finish_requested", occurrenceId,
        mapOf("origin" to origin, "snooze" to snooze, "liveService" to false))
      try {
        if (snooze) AndroidAlarmScheduler.scheduleSnooze(context, ring)
        else AndroidAlarmScheduler.dismiss(context, occurrenceId)
      } catch (error: Throwable) {
        AlarmEventLog.record(context, "ring.finish_failed", occurrenceId,
          mapOf("origin" to origin, "snooze" to snooze, "liveService" to false), error)
        callback?.invoke(AlarmStateStore.snapshot(context))
        return
      }
      AlarmEventLog.record(context, "ring.finish_committed", occurrenceId,
        mapOf("origin" to origin, "snooze" to snooze, "liveService" to false))
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
            origin = "automatic_timeout",
          )
        }
        return
      }
      val current = AlarmStateStore.snapshot(context).ringing ?: return
      if (current.occurrenceId != occurrenceId) return
      val snooze = current.trigger != "test" && current.autoSnoozesUsed < current.alarm.autoSnoozes
      AlarmEventLog.record(context, "ring.finish_requested", occurrenceId,
        mapOf("origin" to "automatic_timeout", "snooze" to snooze, "auto" to true, "liveService" to false))
      try {
        if (snooze) AndroidAlarmScheduler.scheduleSnooze(context, current, current.autoSnoozesUsed + 1)
        else AndroidAlarmScheduler.dismiss(context, occurrenceId)
      } catch (error: Throwable) {
        AlarmEventLog.record(context, "ring.finish_failed", occurrenceId,
          mapOf("origin" to "automatic_timeout", "snooze" to snooze, "auto" to true, "liveService" to false), error)
        return
      }
      AlarmEventLog.record(context, "ring.finish_committed", occurrenceId,
        mapOf("origin" to "automatic_timeout", "snooze" to snooze, "auto" to true, "liveService" to false))
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
    AlarmEventLog.record(context, "input.notification_action", occurrence,
      mapOf("action" to when (intent.action) {
        AlarmPlaybackService.ACTION_SNOOZE -> "snooze"
        AlarmPlaybackService.ACTION_DISMISS -> "dismiss"
        ACTION_AUTO_STOP -> "automatic_timeout"
        else -> "unknown"
      }))
    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      val active = AlarmStateStore.snapshot(context).ringing?.occurrenceId
      Log.d(
        "AerowaveAlarmAction",
        "received action=${intent.action} occurrence=$occurrence active=$active",
      )
    }
    when (intent.action) {
      AlarmPlaybackService.ACTION_SNOOZE -> AlarmPlaybackService.stopIfMatching(context, occurrence, true, origin = "notification")
      AlarmPlaybackService.ACTION_DISMISS -> AlarmPlaybackService.stopIfMatching(context, occurrence, false, origin = "notification")
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
