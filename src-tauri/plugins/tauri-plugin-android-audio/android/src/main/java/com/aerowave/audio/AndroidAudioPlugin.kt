package com.aerowave.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.core.content.ContextCompat
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

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
  var sleepRevision: Long? = null

  internal fun toRequest(): PlayRequest = PlayRequest(
    url = url,
    sourceUrl = sourceUrl,
    title = title,
    stationId = stationId,
    volume = volume.coerceIn(0.0, 1.0).toFloat(),
    generation = generation,
    isHls = isHls,
    sleepRevision = sleepRevision,
  )
}

@InvokeArg
class SetVolumeArgs {
  var volume: Double = 1.0
}

@InvokeArg
class SleepTimerArgs {
  var minutes: Int = 0
}

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
    try {
      val request = invoke.parseArgs(PlayArgs::class.java).toRequest()
      NetworkGuard.validateInitialUrl(request.url, allowLoopback = !request.isHls)
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

  @Command
  fun pause(invoke: Invoke) {
    latestRequestedGeneration = null
    PlaybackService.pause(activity) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun resume(invoke: Invoke) {
    try {
      val state = AudioStateStore.snapshot(activity)
      val request = state.requestOrNull()
      if (request == null) {
        invoke.resolve(state.toJsObject())
        return
      }
      NetworkGuard.validateInitialUrl(request.url, allowLoopback = !request.isHls)
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
    PlaybackService.setVolume(activity, volume) { state ->
      invoke.resolve(state.toJsObject())
    }
  }

  @Command
  fun setSleepTimer(invoke: Invoke) {
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
