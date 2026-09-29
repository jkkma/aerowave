package com.aerowave.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == AndroidAlarmScheduler.ACTION_PREPARE) {
      val id = AndroidAlarmScheduler.readAlarmId(intent) ?: return
      val occurrenceId = AndroidAlarmScheduler.readOccurrenceId(intent) ?: return
      val expectedAt = AndroidAlarmScheduler.readExpectedAt(intent)
      val snoozed = AndroidAlarmScheduler.readSnoozed(intent)
      val candidate = runCatching {
        AlarmPreparation.candidate(context, id, occurrenceId, expectedAt, snoozed)
      }.getOrNull() ?: return
      // The service rechecks after launch; a refused warm-up never alters the AlarmClock.
      try {
        ContextCompat.startForegroundService(
          context,
          AlarmPlaybackService.prepareIntentFor(
            context, candidate.alarmId, candidate.occurrenceId, candidate.atMs, candidate.snoozed,
          ),
        )
      } catch (error: RuntimeException) {
        Log.w("AerowaveAlarmPrepare", "Station preparation could not start", error)
      }
      return
    }

    if (intent.action == AndroidAlarmScheduler.ACTION_FIRE) {
      val id = AndroidAlarmScheduler.readAlarmId(intent) ?: return
      val occurrenceId = AndroidAlarmScheduler.readOccurrenceId(intent) ?: return
      val expectedAt = AndroidAlarmScheduler.readExpectedAt(intent)
      val snoozed = AndroidAlarmScheduler.readSnoozed(intent)
      val ring = AndroidAlarmScheduler.claim(context, id, occurrenceId, expectedAt, snoozed) ?: return
      AlarmPlaybackService.markPending(ring.occurrenceId)
      ContextCompat.startForegroundService(
        context,
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, ring.occurrenceId),
      )
      return
    }

    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_LOCKED_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      Intent.ACTION_TIME_CHANGED,
      Intent.ACTION_TIMEZONE_CHANGED,
      AlarmManagerPermissionReceiver.ACTION_PERMISSION_CHANGED ->
        AndroidAlarmScheduler.rebuild(context, intent.action)
    }
  }
}

/** Manifest alias kept separate so the exact-permission action is explicit. */
internal object AlarmManagerPermissionReceiver {
  const val ACTION_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
}
