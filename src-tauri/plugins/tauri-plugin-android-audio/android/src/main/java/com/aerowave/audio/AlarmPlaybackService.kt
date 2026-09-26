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

class AlarmPlaybackService : Service(), Player.Listener {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var player: ExoPlayer
  private var ring: RingingRecord? = null
  private var desiredVolume = 0.8f
  private var activeFolder: String? = null
  private var currentUri: String? = null
  private var sourceKind = "tone"
  private var sourceIsHls = false
  private var sourceFailure: String? = null
  private val sourceProgress = AlarmPlaybackProgress(SystemClock::elapsedRealtime)
  private var focusPaused = false
  private var focusGranted = false
  private var focusError: String? = null
  private var fallbackStarted = false
  private var userAgent = "Aerowave/0.0.0"
  private lateinit var ringWakeLock: PowerManager.WakeLock
  private val folderExecutor = Executors.newSingleThreadExecutor()
  private var sourceResolutionToken = 0L
  private lateinit var audioManager: AudioManager
  private var audioFocusRequest: AudioFocusRequest? = null
  private var focusMultiplier = 1f

  private val fadeTick = object : Runnable {
    override fun run() {
      val current = ring ?: return
      val fadeMs = current.alarm.fadeSecs * 1000L
      val multiplier = if (fadeMs <= 0) 1f else
        ((SystemClock.elapsedRealtime() - current.startedElapsedMs).toFloat() / fadeMs).coerceIn(0.02f, 1f)
      player.volume = (desiredVolume * multiplier * focusMultiplier).coerceIn(0f, 1f)
      if (multiplier < 1f) handler.postDelayed(this, FADE_TICK_MS)
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
      val failure = sourceProgress.failureReason(
        player.currentPosition,
        player.isPlaying,
        player.playWhenReady,
        sourceKind == "station",
      )
      if (failure != null) {
        handleSourceFailure(failure)
        return
      }
      handler.postDelayed(this, WATCHDOG_TICK_MS)
    }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    audioManager = getSystemService(AudioManager::class.java)
    ringWakeLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:active-alarm")
      .apply { setReferenceCounted(false) }
    createChannel()
    val version = try {
      packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }
    userAgent = "Aerowave/$version"
    val networkFactory = OkHttpDataSource.Factory(NetworkGuard.client(false, userAgent))
      .setUserAgent(userAgent)
      .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
    // DefaultDataSource keeps SAF content URIs local while all HTTP still
    // passes through the same public-network guard used by radio playback.
    val dataSourceFactory = DefaultDataSource.Factory(this, networkFactory)
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      // Media usage follows Android's active media route, including Bluetooth,
      // while the foreground notification keeps the alarm semantics.
      .setUsage(C.USAGE_MEDIA)
      .build()
    player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(this, ChainedOpusExtractorsFactory())
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
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_RING -> startRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID))
      ACTION_SNOOZE -> finishRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID), true, false)
      ACTION_DISMISS -> finishRing(intent.getStringExtra(EXTRA_OCCURRENCE_ID), false, false)
      else -> startRing(null)
    }
    return START_REDELIVER_INTENT
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startRing(expectedOccurrence: String?) {
    val current = AlarmStateStore.snapshot(this).ringing
    if (current == null || (expectedOccurrence != null && current.occurrenceId != expectedOccurrence)) {
      clearPending(expectedOccurrence)
      stopSelf()
      return
    }
    clearPending(current.occurrenceId)
    ring = current
    sourceResolutionToken++
    fallbackStarted = false
    sourceKind = current.sourceKind
    sourceIsHls = current.sourceIsHls
    activeFolder = current.sourceFolder
    currentUri = current.sourceUri
    sourceFailure = current.note
    desiredVolume = current.alarm.volume
    startForeground(NOTIFICATION_ID, notification(current))
    PlaybackService.interruptForAlarm(current.occurrenceId)
    val holdMs = if (current.alarm.autoStopMins > 0) {
      current.alarm.autoStopMins * 60_000L + 60_000L
    } else {
      MAX_RING_WAKE_MS
    }.coerceAtMost(MAX_RING_WAKE_MS)
    if (!ringWakeLock.isHeld) ringWakeLock.acquire(holdMs)
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
    resolvePrimary(current)
  }

  private fun resolvePrimary(current: RingingRecord) {
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
    folderExecutor.execute {
      val result = runCatching { AlarmStreamResolver.resolve(station.url, userAgent) }
      handler.post {
        if (token != sourceResolutionToken || ring?.occurrenceId != occurrence) return@post
        result.fold(
          onSuccess = { resolved ->
            playUri(resolved.url, station.name, null, "station", null, resolved.isHls)
          },
          onFailure = { error ->
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
    if (folder.isNullOrBlank()) playTone(reason) else playFolder(folder, null, reason)
  }

  private fun playTone(reason: String) {
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    if (uri == null) {
      activeFolder = null
      currentUri = null
      sourceKind = "tone"
      sourceIsHls = false
      updateRingingSource(
        "tone", "System alarm", "$reason; no system alarm tone is configured",
        uri = null, folder = null, isHls = false,
      )
      return
    }
    playUri(uri.toString(), "System alarm", null, "tone", reason)
    player.repeatMode = Player.REPEAT_MODE_ONE
  }

  private fun playUri(
    uri: String,
    title: String,
    folder: String?,
    kind: String,
    note: String?,
    isHls: Boolean = uri.substringBefore('?').endsWith(".m3u8", true),
  ) {
    activeFolder = folder
    currentUri = uri
    sourceKind = kind
    sourceIsHls = isHls
    sourceFailure = note
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
    ring?.let {
      NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(it))
    }
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
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
    rejectSourceWithoutAudio()
  }

  private fun rejectSourceWithoutAudio(): Boolean {
    if (ring == null || player.playbackState != Player.STATE_READY) return false
    val tracks = player.currentTracks
    if (tracks.isEmpty) return false
    val selectedAudio = tracks.groups.any { group ->
      group.type == C.TRACK_TYPE_AUDIO &&
        (0 until group.length).any { index ->
          group.isTrackSelected(index) && group.isTrackSupported(index)
        }
    }
    if (selectedAudio) return false
    handleSourceFailure("The selected alarm source has no playable audio track")
    return true
  }

  private fun handleSourceFailure(reason: String) {
    handler.removeCallbacks(progressWatchdog)
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

  private fun finishRing(
    occurrenceId: String?,
    snooze: Boolean,
    auto: Boolean,
  ): PersistedAlarmState? {
    val current = ring ?: AlarmStateStore.snapshot(this).ringing ?: return null
    if (occurrenceId != null && occurrenceId != current.occurrenceId) return null
    handler.removeCallbacks(fadeTick)
    handler.removeCallbacks(autoStop)
    sourceResolutionToken++
    player.stop()
    player.clearMediaItems()
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
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
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
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun notification(current: RingingRecord): Notification {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
      ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
    launchIntent.putExtra(EXTRA_ALARM_LAUNCH, true)
    val launch = PendingIntent.getActivity(
      this, 71_000, launchIntent,
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
      when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> {
          focusPaused = false
          focusGranted = true
          focusError = null
          focusMultiplier = 1f
          sourceProgress.resumeFromFocus(player.currentPosition)
          if (ring != null && currentUri != null && !player.isPlaying) player.play()
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
          sourceProgress.pauseForFocus()
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
  }

  private fun abandonAlarmFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
      audioFocusRequest = null
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusListener)
    }
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
    handler.removeCallbacksAndMessages(null)
    sourceResolutionToken++
    player.removeListener(this)
    player.release()
    abandonAlarmFocus()
    if (ringWakeLock.isHeld) ringWakeLock.release()
    folderExecutor.shutdownNow()
    instance = null
    super.onDestroy()
  }

  companion object {
    const val ACTION_RING = "com.aerowave.audio.action.RING"
    const val ACTION_SNOOZE = "com.aerowave.audio.action.SNOOZE_ALARM"
    const val ACTION_DISMISS = "com.aerowave.audio.action.DISMISS_ALARM"
    private const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    const val EXTRA_ALARM_LAUNCH = "aerowaveAlarm"
    private const val CHANNEL_ID = "aerowave_alarms_v1"
    internal const val NOTIFICATION_ID = 71_001
    private const val FADE_TICK_MS = 250L
    private const val WATCHDOG_TICK_MS = 1_000L
    private const val MAX_RING_WAKE_MS = 2 * 60 * 60 * 1000L

    @Volatile private var instance: AlarmPlaybackService? = null
    @Volatile private var pendingOccurrenceId: String? = null

    @JvmStatic
    fun isRinging(): Boolean = instance?.ring != null

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
