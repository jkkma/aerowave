package com.aerowave.audio

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebView
import android.view.WindowManager
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.ActivityCallback
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val NOTIFICATIONS_PERMISSION = "notifications"

@InvokeArg
class PlayArgs {
  lateinit var url: String
  lateinit var sourceUrl: String
  lateinit var title: String
  var stationId: String? = null
  var volume: Double = 1.0
  var generation: Long = 0
  var isHls: Boolean = false
  var showMetadata: Boolean = true
  var artworkDataUrl: String? = null
  var sleepRevision: Long? = null
  var sourceFolder: String? = null
  var backupFolder: String? = null

  internal fun toRequest(): PlayRequest = PlayRequest(
    url = url,
    sourceUrl = sourceUrl,
    title = title,
    stationId = stationId,
    volume = volume.coerceIn(0.0, 1.0).toFloat(),
    generation = generation,
    isHls = isHls,
    showMetadata = showMetadata,
    artworkDataUrl = PlaybackArtworkValidator.parse(artworkDataUrl)?.dataUrl,
    sleepRevision = sleepRevision,
    sourceFolder = sourceFolder,
    backupFolder = backupFolder,
  )
}

@InvokeArg
class SetVolumeArgs {
  var volume: Double = 1.0
}

@InvokeArg
class ArtworkArgs {
  var generation: Long = 0
  lateinit var sourceUrl: String
  var artworkDataUrl: String? = null
}

@InvokeArg
class MetadataEnabledArgs {
  var generation: Long = 0
  lateinit var sourceUrl: String
  var enabled: Boolean = true
}

@InvokeArg
class StreamTitleArgs {
  lateinit var sourceUrl: String
  var title: String = ""
}

@InvokeArg
class SleepTimerArgs {
  var minutes: Int = 0
}

@InvokeArg
class SyncAlarmsArgs {
  var alarmsJson: String? = null
  var expectedRevision: Long? = null
  lateinit var stationsJson: String
  var backupFolder: String? = null
}

@InvokeArg
class SaveBackupFileArgs { lateinit var content: String }

@InvokeArg
class AlarmIdArgs {
  lateinit var id: String
  var occurrenceId: String? = null
}

@InvokeArg
class SkipAlarmArgs {
  lateinit var id: String
  var skip: Boolean = false
  var expectedAtMs: Long = -1
  var expectedRevision: Long? = null
}

@InvokeArg
class TestAlarmArgs { lateinit var alarmJson: String }

@InvokeArg
class AlarmSettingsArgs { lateinit var setting: String }

@TauriPlugin(
  permissions = [
    Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = NOTIFICATIONS_PERMISSION),
  ],
)
class AndroidAudioPlugin(private val activity: Activity) : Plugin(activity) {
  private var latestRequestedGeneration: Long? = null

  override fun load(webView: WebView) {
    super.load(webView)
    val restoredFromPreviousProcess = AudioStateStore.restoredFromPreviousProcess(activity)
    val stale = AudioStateStore.snapshot(activity)
    if (restoredFromPreviousProcess &&
      !PlaybackService.isRunning() &&
      !stale.isHls &&
      NetworkGuard.isLoopbackUrl(stale.playableUrl)
    ) {
      AudioStateStore.update(activity) {
        it.copy(status = STATUS_PAUSED, playableUrl = "", error = null)
      }
      return
    }
    if (!PlaybackService.isRunning() &&
      (stale.status == STATUS_PLAYING || stale.status == STATUS_BUFFERING)
    ) {
      AudioStateStore.update(activity) { it.copy(status = STATUS_PAUSED, error = null) }
    }
  }

  @Command
  fun play(invoke: Invoke) {
    if (rejectWhileAlarmRings(invoke)) return
    try {
      val request = invoke.parseArgs(PlayArgs::class.java).toRequest()
      validatePlayRequest(request)
      latestRequestedGeneration = request.generation
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        getPermissionState(NOTIFICATIONS_PERMISSION) != PermissionState.GRANTED
      ) {
        requestPermissionForAlias(NOTIFICATIONS_PERMISSION, invoke, "notificationPermissionResult")
        return
      }
      dispatchPlay(invoke, request)
    } catch (error: Exception) {
      failPlay(invoke, error)
    }
  }

  @PermissionCallback
  private fun notificationPermissionResult(invoke: Invoke) {
    // Android permits media foreground services when the user declines this
    // permission, but keeps their notification out of the notification drawer.
    try {
      if (rejectWhileAlarmRings(invoke)) return
      val request = invoke.parseArgs(PlayArgs::class.java).toRequest()
      if (latestRequestedGeneration != request.generation) {
        invoke.resolve(AudioStateStore.snapshot(activity).toJsObject())
        return
      }
      dispatchPlay(invoke, request)
    } catch (error: Exception) {
      failPlay(invoke, error)
    }
  }

  private fun dispatchPlay(invoke: Invoke, request: PlayRequest) {
    try {
      if (rejectWhileAlarmRings(invoke)) return
      if (PlaybackService.play(request) { state -> invoke.resolve(state.toJsObject()) }) return
      val stale = AudioStateStore.snapshot(activity)
      if (request.sleepRevision != null &&
        stale.sleepTimer.lastFinishedRevision > request.sleepRevision!!
      ) {
        invoke.resolve(AudioStateStore.stop(activity).toJsObject())
        return
      }
      val state = AudioStateStore.begin(activity, request)
      ContextCompat.startForegroundService(
        activity,
        PlaybackService.intentFor(activity, PlaybackService.ACTION_PLAY, request),
      )
      invoke.resolve(state.toJsObject())
    } catch (error: Exception) {
      failPlay(invoke, error)
    }
  }

  private fun failPlay(invoke: Invoke, error: Exception) {
    AudioStateStore.update(activity) {
      it.copy(status = STATUS_ERROR, error = error.message ?: "Unable to start playback")
    }
    invoke.reject(error.message ?: "Unable to start playback")
  }

  private fun validatePlayRequest(request: PlayRequest) {
    val uri = Uri.parse(request.url)
    if (uri.scheme == "content") {
      require(request.sourceFolder != null) { "A document track must include its source folder" }
      // Tracks enter through randomTrack, which performs the provider and
      // subtree checks off the UI thread. Media3 reopens the persisted URI;
      // recovery scans validate any replacement on PlaybackService's worker.
    } else {
      NetworkGuard.validateInitialUrl(request.url, allowLoopback = !request.isHls)
    }
  }

  @Command
  fun pause(invoke: Invoke) {
    latestRequestedGeneration = null
    PlaybackService.pause(activity) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun resume(invoke: Invoke) {
    if (rejectWhileAlarmRings(invoke)) return
    try {
      val state = AudioStateStore.snapshot(activity)
      val request = state.requestOrNull()
      if (request == null) {
        invoke.resolve(state.toJsObject())
        return
      }
      validatePlayRequest(request)
      val buffering = AudioStateStore.update(activity) {
        it.copy(status = STATUS_BUFFERING, error = null)
      }
      ContextCompat.startForegroundService(
        activity,
        PlaybackService.intentFor(activity, PlaybackService.ACTION_RESUME),
      )
      invoke.resolve(buffering.toJsObject())
    } catch (error: Exception) {
      val failed = AudioStateStore.update(activity) {
        it.copy(status = STATUS_ERROR, error = error.message ?: "Unable to resume playback")
      }
      invoke.resolve(failed.toJsObject())
    }
  }

  @Command
  fun stop(invoke: Invoke) {
    latestRequestedGeneration = null
    PlaybackService.stop(activity) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun getState(invoke: Invoke) {
    PlaybackService.snapshot(activity) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun setVolume(invoke: Invoke) {
    val volume = invoke.parseArgs(SetVolumeArgs::class.java).volume.coerceIn(0.0, 1.0).toFloat()
    AlarmPlaybackService.setVolume(volume)
    PlaybackService.setVolume(activity, volume) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun updateArtwork(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(ArtworkArgs::class.java)
      val artwork = PlaybackArtworkValidator.parse(args.artworkDataUrl)
      PlaybackService.updateArtwork(
        activity,
        args.generation,
        args.sourceUrl,
        artwork,
      ) { state -> invoke.resolve(state.toJsObject()) }
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to update Android artwork")
    }
  }

  @Command
  fun setMetadataEnabled(invoke: Invoke) {
    val args = invoke.parseArgs(MetadataEnabledArgs::class.java)
    PlaybackService.setMetadataEnabled(
      activity,
      args.generation,
      args.sourceUrl,
      args.enabled,
    ) { state -> invoke.resolve(state.toJsObject()) }
  }

  @Command
  fun updateStreamTitle(invoke: Invoke) {
    val args = invoke.parseArgs(StreamTitleArgs::class.java)
    PlaybackService.updateStreamTitle(activity, args.sourceUrl, args.title) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun setSleepTimer(invoke: Invoke) {
    if (rejectWhileAlarmRings(invoke)) return
    val minutes = invoke.parseArgs(SleepTimerArgs::class.java).minutes
    PlaybackService.setSleepTimer(
      activity,
      minutes,
      onSuccess = { state -> invoke.resolve(state.sleepTimer.toJsObject()) },
      onError = invoke::reject,
    )
  }

  @Command
  fun cancelSleepTimer(invoke: Invoke) {
    PlaybackService.cancelSleepTimer(activity) { state ->
      invoke.resolve(state.sleepTimer.toJsObject())
    }
  }

  @Command
  fun getSleepTimer(invoke: Invoke) {
    PlaybackService.snapshot(activity) { state ->
      invoke.resolve(state.sleepTimer.toJsObject())
    }
  }

  private fun rejectWhileAlarmRings(invoke: Invoke): Boolean {
    if (!AlarmPlaybackService.isRinging() && AlarmStateStore.snapshot(activity).ringing == null) {
      return false
    }
    invoke.reject("Dismiss or snooze the ringing alarm before starting regular playback")
    return true
  }

  @Command
  fun syncAlarms(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(SyncAlarmsArgs::class.java)
      val stations = JSONArray(args.stationsJson).mapObjects(::stationFromJson)
      val incoming = args.alarmsJson?.let { JSONArray(it).mapObjects(::alarmFromJson) }
      if (incoming != null) {
        require(incoming.map { it.id }.distinct().size == incoming.size) { "Alarm ids must be unique" }
      }
      var previousAlarms: List<NativeAlarm> = emptyList()
      val changed = AlarmStateStore.update(activity) { old ->
        if (incoming == null && !old.initialized && old.error != null) {
          throw IllegalStateException(old.error)
        }
        if (args.expectedRevision != null &&
          old.revision != args.expectedRevision
        ) {
          throw IllegalStateException("Alarms changed on Android; refresh and try again")
        }
        previousAlarms = old.alarms
        val merged = incoming?.let { AlarmStateTransitions.syncSkipDates(old.alarms, it) }
        val cancelled = merged?.let {
          AlarmStateTransitions.cancelledAlarmIds(old.alarms, it)
        }.orEmpty()
        old.copy(
          initialized = old.initialized || incoming != null,
          revision = old.revision + 1,
          alarms = merged ?: old.alarms,
          stations = stations,
          backupFolder = args.backupFolder,
          snoozes = old.snoozes - cancelled,
          error = null,
        )
      }
      val result = if (incoming != null) {
        previousAlarms.forEach { AndroidAlarmScheduler.cancelAlarm(activity, it.id) }
        AndroidAlarmScheduler.rebuild(activity, "sync")
      } else changed
      invoke.resolve(result.toAlarmJsObject(activity))
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to save Android alarms")
    }
  }

  @Command
  fun restoreAlarms(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(SyncAlarmsArgs::class.java)
      val incoming = args.alarmsJson?.let { JSONArray(it).mapObjects(::alarmFromJson) }
        ?: throw IllegalArgumentException("Backup must include alarms")
      val stations = JSONArray(args.stationsJson).mapObjects(::stationFromJson)
      var previousAlarmIds: Set<String> = emptySet()
      val changed = AlarmStateStore.update(activity) { old ->
        previousAlarmIds = old.alarms.mapTo(mutableSetOf()) { it.id } +
          old.scheduled.keys + old.snoozes.keys
        AlarmStateTransitions.restore(
          old, incoming, stations, args.backupFolder, args.expectedRevision,
          AlarmPlaybackService.activeOccurrenceId(),
        )
      }
      // The replacement is durable before any old alarm clock is cancelled.
      // A receiver racing this cleanup can no longer claim its old occurrence.
      previousAlarmIds.forEach { AndroidAlarmScheduler.cancelAlarm(activity, it) }
      invoke.resolve(changed.toAlarmJsObject(activity))
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to restore Android alarms")
    }
  }

  @Command
  fun getAlarmState(invoke: Invoke) {
    val state = AlarmStateStore.snapshot(activity)
    clearAlarmWindowIfIdle(state)
    invoke.resolve(state.toAlarmJsObject(activity))
  }

  @Command
  fun skipAlarm(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(SkipAlarmArgs::class.java)
      val now = System.currentTimeMillis()
      val changed = AlarmStateStore.update(activity) { old ->
        AlarmStateTransitions.skipAlarm(
          old, args.id, args.skip, args.expectedAtMs, args.expectedRevision, now,
        )
      }
      AndroidAlarmScheduler.replaceRegular(activity, args.id)
      invoke.resolve(changed.toAlarmJsObject(activity))
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to change the skipped alarm")
    }
  }

  @Command
  fun snoozeAlarm(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(AlarmIdArgs::class.java)
      val ring = requireMatchingRing(args)
      AlarmPlaybackService.stopIfMatching(activity, ring.occurrenceId, true) { state ->
        clearAlarmWindowIfIdle(state)
        invoke.resolve(state.toAlarmJsObject(activity))
      }
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to snooze the alarm")
    }
  }

  @Command
  fun dismissAlarm(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(AlarmIdArgs::class.java)
      val ring = requireMatchingRing(args)
      AlarmPlaybackService.stopIfMatching(activity, ring.occurrenceId, false) { state ->
        clearAlarmWindowIfIdle(state)
        invoke.resolve(state.toAlarmJsObject(activity))
      }
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to dismiss the alarm")
    }
  }

  @Command
  fun testAlarm(invoke: Invoke) {
    try {
      val alarm = alarmFromJson(JSONObject(invoke.parseArgs(TestAlarmArgs::class.java).alarmJson))
      require(AlarmStateStore.snapshot(activity).ringing == null) { "Another alarm is already ringing" }
      val now = System.currentTimeMillis()
      val ring = RingingRecord(
        alarm = alarm,
        occurrenceId = "test:${alarm.id}:$now:${UUID.randomUUID()}",
        trigger = "test",
        startedAtMs = now,
        startedElapsedMs = SystemClock.elapsedRealtime(),
      )
      val state = AlarmStateStore.update(activity) { old ->
        old.copy(revision = old.revision + 1, ringing = ring, error = null)
      }
      AlarmPlaybackService.markPending(ring.occurrenceId)
      ContextCompat.startForegroundService(
        activity,
        AlarmPlaybackService.intentFor(activity, AlarmPlaybackService.ACTION_RING, ring.occurrenceId),
      )
      invoke.resolve(state.toAlarmJsObject(activity))
    } catch (error: Exception) {
      AlarmPlaybackService.clearPending()
      invoke.reject(error.message ?: "Unable to test the alarm")
    }
  }

  @Command
  fun openAlarmSettings(invoke: Invoke) {
    try {
      val setting = invoke.parseArgs(AlarmSettingsArgs::class.java).setting
      val intent = when (setting) {
        "exact" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${activity.packageName}"))
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:${activity.packageName}"))
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:${activity.packageName}"))
        "fullscreen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${activity.packageName}"))
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        else -> throw IllegalArgumentException("Unknown alarm setting")
      }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      activity.startActivity(intent)
      invoke.resolve(AlarmStateStore.snapshot(activity).toAlarmJsObject(activity))
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to open Android settings")
    }
  }

  @Command
  fun pickFolder(invoke: Invoke) =
    startActivityForResult(invoke, LibraryCommands.folderPickerIntent(), "pickFolderResult")

  @ActivityCallback
  fun pickFolderResult(invoke: Invoke, result: ActivityResult) =
    LibraryCommands.handleFolderPickerResult(activity, invoke, result)

  @Command
  fun folderInfo(invoke: Invoke) = LibraryCommands.folderInfo(activity, invoke)

  @Command
  fun randomTrack(invoke: Invoke) = LibraryCommands.randomTrack(activity, invoke)

  @Command
  fun readBackupFile(invoke: Invoke) =
    startActivityForResult(invoke, BackupDocuments.openIntent(), "readBackupFileResult")

  @ActivityCallback
  fun readBackupFileResult(invoke: Invoke, result: ActivityResult) =
    BackupDocuments.readResult(activity, invoke, result)

  @Command
  fun saveBackupFile(invoke: Invoke) {
    try {
      BackupDocuments.encodeUtf8(invoke.parseArgs(SaveBackupFileArgs::class.java).content)
      startActivityForResult(invoke, BackupDocuments.createIntent(), "saveBackupFileResult")
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to save backup")
    }
  }

  @ActivityCallback
  fun saveBackupFileResult(invoke: Invoke, result: ActivityResult) {
    try {
      val content = invoke.parseArgs(SaveBackupFileArgs::class.java).content
      BackupDocuments.writeResult(activity, invoke, result, content)
    } catch (error: Exception) {
      invoke.reject(error.message ?: "Unable to save backup")
    }
  }

  private fun requireMatchingRing(args: AlarmIdArgs): RingingRecord {
    val ring = AlarmStateStore.snapshot(activity).ringing
      ?: throw IllegalStateException("That alarm is no longer ringing")
    require(ring.alarm.id == args.id) { "That alarm is no longer ringing" }
    require(args.occurrenceId == null || args.occurrenceId == ring.occurrenceId) {
      "That alarm occurrence is no longer ringing"
    }
    return ring
  }

  private fun clearAlarmWindowIfIdle(state: PersistedAlarmState) {
    if (state.ringing != null) return
    activity.runOnUiThread {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        activity.setShowWhenLocked(false)
        activity.setTurnScreenOn(false)
      } else {
        @Suppress("DEPRECATION")
        activity.window.clearFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
      }
    }
  }
}

private fun PlaybackSnapshot.toJsObject(): JSObject = JSObject().apply {
  put("status", status)
  put("generation", generation)
  put("sourceUrl", sourceUrl)
  put("title", title)
  put("stationId", stationId)
  put("positionMs", positionMs)
  put("volume", volume.toDouble())
  put("error", error)
  put("trackTitle", trackTitle)
  put("sleepTimer", sleepTimer.toJsObject())
  put("sourceFolder", sourceFolder)
  put("isHls", isHls)
  put("showMetadata", showMetadata)
}

private fun SleepTimerSnapshot.toJsObject(): JSObject = JSObject().apply {
  put("revision", revision)
  put("timer", timer?.toJsObject())
  put("outcome", outcome)
  put("error", error)
}

private fun SleepTimerInfo.toJsObject(): JSObject = JSObject().apply {
  put("minutes", minutes)
  put("action", "stop")
  put("endsAtMs", endsAtMs)
  put("executeAtMs", null)
  put("remainingMs", remainingMs)
}

private fun PersistedAlarmState.toAlarmJsObject(context: Context): JSObject = JSObject().apply {
  put("initialized", initialized)
  put("revision", revision)
  put("alarms", JSONArray().also { out -> alarms.forEach { out.put(it.toJson()) } })
  val now = System.currentTimeMillis()
  put("occurrences", JSONArray().also { out -> alarms.forEach { alarm ->
    out.put(JSObject().apply {
      put("alarmId", alarm.id)
      put("nextAtMs", if (alarm.enabled) AlarmSchedule.nextAt(alarm, now) else null)
      put("skippedAtMs", AlarmStateTransitions.skippedAt(alarm, now))
    })
  } })
  val upcoming = (scheduled.values + snoozes.values).minByOrNull { it.atMs }
  put("next", upcoming?.let { occurrence ->
    val alarm = alarms.find { it.id == occurrence.alarmId }
    JSObject().apply {
      put("alarmId", occurrence.alarmId)
      put("label", alarm?.label ?: "")
      put("atMs", occurrence.atMs)
      put("snoozed", occurrence.snoozed)
    }
  })
  put("ringing", ringing?.let { current -> JSObject().apply {
    put("alarmId", current.alarm.id)
    put("occurrenceId", current.occurrenceId)
    put("label", current.alarm.label)
    put("trigger", current.trigger)
    put("startedAtMs", current.startedAtMs)
    put("hour", current.alarm.hour)
    put("minute", current.alarm.minute)
    put("snoozeMins", current.alarm.snoozeMins)
    put("volume", current.alarm.volume.toDouble())
    put("canSnooze", current.trigger != "test" && current.alarm.snoozeMins > 0)
    put("autoSnoozesRemaining", (current.alarm.autoSnoozes - current.autoSnoozesUsed).coerceAtLeast(0))
    put("sourceKind", current.sourceKind)
    put("title", current.title)
    put("note", current.note)
  } })
  put("permissions", alarmPermissions(context))
  put("error", error)
}

private fun alarmPermissions(context: Context): JSObject = JSObject().apply {
  put("exact", when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "notRequired"
    AndroidAlarmScheduler.canScheduleExact(context) -> "granted"
    else -> "denied"
  })
  put("notifications", when {
    !AlarmPlaybackService.alarmChannelEnabled(context) -> "denied"
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "notRequired"
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED -> "granted"
    else -> "denied"
  })
  put("fullScreen", when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "notRequired"
    context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() -> "granted"
    else -> "denied"
  })
  val power = context.getSystemService(PowerManager::class.java)
  put("batteryOptimized", !power.isIgnoringBatteryOptimizations(context.packageName))
}
