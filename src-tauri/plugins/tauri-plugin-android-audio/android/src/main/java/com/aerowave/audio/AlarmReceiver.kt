package com.aerowave.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val occurrenceId = AndroidAlarmScheduler.readOccurrenceId(intent)
    AlarmEventLog.recordSystemContext(context, "receiver.received", occurrenceId, mapOf(
      "action" to actionName(intent.action),
      "expectedAtMs" to AndroidAlarmScheduler.readExpectedAt(intent),
      "snoozed" to AndroidAlarmScheduler.readSnoozed(intent),
    ))
    try {
      dispatch(context, intent)
    } catch (error: RuntimeException) {
      AlarmEventLog.record(context, "receiver.dispatch_failed", occurrenceId,
        mapOf("action" to actionName(intent.action)), error)
      throw error
    }
  }

  private fun dispatch(context: Context, intent: Intent) {
    if (intent.action == AndroidAlarmScheduler.ACTION_PREPARE) {
      val id = readAlarmId(context, intent) ?: return
      val occurrenceId = readOccurrenceId(context, intent) ?: return
      val expectedAt = AndroidAlarmScheduler.readExpectedAt(intent)
      val snoozed = AndroidAlarmScheduler.readSnoozed(intent)
      val lookup = runCatching {
        AlarmPreparation.candidate(context, id, occurrenceId, expectedAt, snoozed)
      }
      val candidate = lookup.getOrNull()
      if (candidate == null) {
        AlarmEventLog.record(context, "receiver.preparation_rejected", occurrenceId, mapOf(
          "reason" to if (lookup.isFailure) "lookup_failed" else "stale_or_unavailable",
          "expectedAtMs" to expectedAt,
          "snoozed" to snoozed,
        ), lookup.exceptionOrNull())
        return
      }
      // The service rechecks after launch; a refused warm-up never alters the AlarmClock.
      try {
        startService(
          context,
          AlarmPlaybackService.prepareIntentFor(
            context, candidate.alarmId, candidate.occurrenceId, candidate.atMs, candidate.snoozed,
          ),
          candidate.occurrenceId,
          "prepare",
        )
      } catch (error: RuntimeException) {
        Log.w("AerowaveAlarmPrepare", "Station preparation could not start", error)
      }
      return
    }

    if (intent.action == AndroidAlarmScheduler.ACTION_FIRE) {
      val id = readAlarmId(context, intent) ?: return
      val occurrenceId = readOccurrenceId(context, intent) ?: return
      val expectedAt = AndroidAlarmScheduler.readExpectedAt(intent)
      val snoozed = AndroidAlarmScheduler.readSnoozed(intent)
      val ring = AndroidAlarmScheduler.claim(context, id, occurrenceId, expectedAt, snoozed)
      if (ring == null) {
        AlarmEventLog.record(context, "receiver.fire_rejected", occurrenceId,
          mapOf("reason" to "not_claimed"))
        return
      }
      AlarmPlaybackService.markPending(ring.occurrenceId)
      startService(
        context,
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, ring.occurrenceId),
        ring.occurrenceId,
        "ring",
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
      else -> AlarmEventLog.record(context, "receiver.ignored", fields = mapOf("reason" to "unknown_action"))
    }
  }

  private fun startService(context: Context, intent: Intent, occurrenceId: String, mode: String) {
    AlarmEventLog.record(context, "receiver.service_start_requested", occurrenceId, mapOf("mode" to mode))
    try {
      AlarmServiceStarter.start(context, intent)
      AlarmEventLog.record(context, "receiver.service_start_accepted", occurrenceId, mapOf("mode" to mode))
    } catch (error: RuntimeException) {
      AlarmEventLog.recordSystemContext(context, "receiver.service_start_failed", occurrenceId,
        mapOf("mode" to mode), error)
      throw error
    }
  }

  private fun readAlarmId(context: Context, intent: Intent): String? =
    AndroidAlarmScheduler.readAlarmId(intent).also { id ->
      if (id == null) AlarmEventLog.record(context, "receiver.ignored",
        AndroidAlarmScheduler.readOccurrenceId(intent), mapOf("reason" to "missing_alarm_id"))
    }

  private fun readOccurrenceId(context: Context, intent: Intent): String? =
    AndroidAlarmScheduler.readOccurrenceId(intent).also { occurrenceId ->
      if (occurrenceId == null) AlarmEventLog.record(context, "receiver.ignored",
        fields = mapOf("reason" to "missing_occurrence_id"))
    }

  private fun actionName(action: String?): String = when (action) {
    AndroidAlarmScheduler.ACTION_FIRE -> "fire"
    AndroidAlarmScheduler.ACTION_PREPARE -> "prepare"
    Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
    Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot_completed"
    Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
    Intent.ACTION_TIME_CHANGED -> "clock_changed"
    Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
    AlarmManagerPermissionReceiver.ACTION_PERMISSION_CHANGED -> "exact_access_changed"
    else -> "other"
  }
}

/** Manifest alias kept separate so the exact-permission action is explicit. */
internal object AlarmManagerPermissionReceiver {
  const val ACTION_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
}
