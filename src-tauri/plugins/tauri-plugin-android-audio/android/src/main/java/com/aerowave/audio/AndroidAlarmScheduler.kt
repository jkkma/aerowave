package com.aerowave.audio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock

internal object AndroidAlarmScheduler {
  const val ACTION_FIRE = "com.aerowave.audio.action.FIRE_ALARM"
  private const val EXTRA_ALARM_ID = "alarmId"
  private const val EXTRA_OCCURRENCE_ID = "occurrenceId"
  private const val EXTRA_EXPECTED_AT = "expectedAt"
  private const val EXTRA_SNOOZED = "snoozed"

  fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
  }

  /** Recompute wall-clock occurrences after edits, reboot, time or zone changes. */
  fun rebuild(context: Context, reason: String? = null): PersistedAlarmState {
    val before = AlarmStateStore.snapshot(context)
    // A malformed durable record is evidence, not an empty schedule. Keep the
    // raw SharedPreferences entry untouched until an explicit alarm save.
    if (!before.initialized && before.error != null) return before
    cancelKnown(context, before)
    val now = System.currentTimeMillis()
    val exact = canScheduleExact(context)
    val activeOccurrenceId = AlarmPlaybackService.activeOccurrenceId()
    val changed = AlarmStateStore.update(context) { old ->
      val ringDecision = AlarmStateTransitions.ringDuringRebuild(old, now, activeOccurrenceId)
      val scheduled = old.alarms.asSequence()
        .filter { it.enabled }
        .mapNotNull { alarm ->
          AlarmStateTransitions.nextScheduled(alarm, old.scheduled[alarm.id], now)
            ?.let { alarm.id to it }
        }.toMap()
      val snoozes = old.snoozes.filter { (id, occurrence) ->
        old.alarms.any { it.id == id } &&
          (occurrence.atMs > now || AlarmSchedule.isDeliverable(occurrence.atMs, now))
      } + listOfNotNull(ringDecision.recoveredSnooze).associateBy { it.alarmId }
      old.copy(
        revision = old.revision + 1,
        scheduled = scheduled,
        snoozes = snoozes,
        ringing = ringDecision.ringing,
        error = if (exact) null else "Exact alarm access is off; Android cannot deliver alarms on time",
      )
    }
    if (exact) schedulePersisted(context, changed)
    return changed
  }

  fun claim(
    context: Context,
    alarmId: String,
    occurrenceId: String,
    expectedAt: Long,
    snoozed: Boolean,
  ): RingingRecord? {
    val now = System.currentTimeMillis()
    var claimed: RingingRecord? = null
    val changed = AlarmStateStore.update(context) { old ->
      val expected = (if (snoozed) old.snoozes else old.scheduled)[alarmId]
      val alarm = old.alarms.find { it.id == alarmId }
      if (expected == null || alarm == null || expected.occurrenceId != occurrenceId ||
        expected.atMs != expectedAt || (!snoozed && !alarm.enabled)) {
        return@update old
      }
      if (!AlarmSchedule.isDeliverable(expectedAt, now)) {
        return@update AlarmStateTransitions.expire(old, alarm, expected, now)
      }
      // If two alarm clocks share a minute, keep the second durable instead
      // of replacing the notification/actions of the one already ringing.
      if (old.ringing != null) {
        return@update AlarmStateTransitions.deferBehindActive(old, expected, now)
      }
      val disabled = if (!snoozed && alarm.days.isEmpty()) alarm.copy(enabled = false) else alarm
      val ring = RingingRecord(
        alarm = disabled,
        occurrenceId = occurrenceId,
        trigger = if (snoozed) "snooze" else "scheduled",
        startedAtMs = now,
        startedElapsedMs = SystemClock.elapsedRealtime(),
        autoSnoozesUsed = expected.autoSnoozesUsed,
        sourceUri = expected.heldUri,
        sourceFolder = expected.heldFolder,
        sourceKind = expected.heldKind ?: "tone",
        title = expected.heldTitle,
        note = expected.heldNote,
        sourceIsHls = expected.heldIsHls,
      )
      claimed = ring
      val alarms = old.alarms.map { if (it.id == alarmId) disabled else it }
      val nextScheduled = old.scheduled.toMutableMap().apply { remove(alarmId) }
      if (disabled.enabled && disabled.days.isNotEmpty()) {
        AlarmSchedule.nextAt(disabled, now)?.let { at ->
          nextScheduled[alarmId] = ScheduledOccurrence(
            alarmId, AlarmSchedule.occurrenceId(alarmId, at, false), at, false,
          )
        }
      }
      old.copy(
        revision = old.revision + 1,
        alarms = alarms,
        scheduled = nextScheduled,
        snoozes = old.snoozes - alarmId,
        ringing = ring,
        error = null,
      )
    }
    if (canScheduleExact(context)) schedulePersisted(context, changed)
    return claimed
  }

  fun scheduleSnooze(
    context: Context,
    ring: RingingRecord,
    autoSnoozesUsed: Int = ring.autoSnoozesUsed,
  ): PersistedAlarmState {
    if (ring.trigger == "test") return dismiss(context, ring.occurrenceId)
    val at = System.currentTimeMillis() + ring.alarm.snoozeMins.coerceAtLeast(1) * 60_000L
    val occurrence = ScheduledOccurrence(
      ring.alarm.id, AlarmSchedule.occurrenceId(ring.alarm.id, at, true), at, true,
      autoSnoozesUsed,
      ring.sourceUri,
      ring.title,
      ring.sourceFolder,
      ring.sourceKind,
      ring.note,
      ring.sourceIsHls,
    )
    val changed = AlarmStateStore.update(context) { old ->
      AlarmStateTransitions.snooze(old, ring, occurrence, canScheduleExact(context))
    }
    if (canScheduleExact(context) &&
      changed.snoozes[ring.alarm.id]?.occurrenceId == occurrence.occurrenceId
    ) schedule(context, occurrence)
    return changed
  }

  fun dismiss(context: Context, occurrenceId: String): PersistedAlarmState =
    AlarmStateStore.update(context) { old ->
      if (old.ringing?.occurrenceId != occurrenceId) old else old.copy(
        revision = old.revision + 1,
        ringing = null,
        error = null,
      )
    }

  fun cancelAlarm(context: Context, alarmId: String) {
    cancel(context, alarmId, false)
    cancel(context, alarmId, true)
  }

  private fun schedulePersisted(context: Context, state: PersistedAlarmState) {
    (state.scheduled.values + state.snoozes.values).forEach { schedule(context, it) }
  }

  private fun cancelKnown(context: Context, state: PersistedAlarmState) {
    (state.scheduled.keys + state.snoozes.keys + state.alarms.map { it.id }).toSet().forEach {
      cancel(context, it, false)
      cancel(context, it, true)
    }
  }

  private fun schedule(context: Context, occurrence: ScheduledOccurrence) {
    if (!canScheduleExact(context)) return
    val operation = pendingIntent(context, occurrence, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
    val show = PendingIntent.getActivity(
      context, 70_001, launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    context.getSystemService(AlarmManager::class.java).setAlarmClock(
      AlarmManager.AlarmClockInfo(occurrence.atMs, show), operation,
    )
  }

  private fun cancel(context: Context, alarmId: String, snoozed: Boolean) {
    val placeholder = ScheduledOccurrence(alarmId, "", 0, snoozed)
    val operation = pendingIntent(context, placeholder, PendingIntent.FLAG_NO_CREATE) ?: return
    context.getSystemService(AlarmManager::class.java).cancel(operation)
    operation.cancel()
  }

  private fun pendingIntent(
    context: Context,
    occurrence: ScheduledOccurrence,
    lookupFlag: Int,
  ): PendingIntent? {
    val kind = if (occurrence.snoozed) "snooze" else "scheduled"
    val intent = Intent(context, AlarmReceiver::class.java)
      .setAction(ACTION_FIRE)
      .setData(Uri.Builder().scheme("aerowave").authority("alarm")
        .appendPath(kind).appendPath(occurrence.alarmId).build())
      .putExtra(EXTRA_ALARM_ID, occurrence.alarmId)
      .putExtra(EXTRA_OCCURRENCE_ID, occurrence.occurrenceId)
      .putExtra(EXTRA_EXPECTED_AT, occurrence.atMs)
      .putExtra(EXTRA_SNOOZED, occurrence.snoozed)
    val requestCode = (occurrence.alarmId.hashCode() * 31 + kind.hashCode()) and 0x7fffffff
    return PendingIntent.getBroadcast(
      context, requestCode, intent,
      lookupFlag or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  fun readAlarmId(intent: Intent): String? = intent.getStringExtra(EXTRA_ALARM_ID)
  fun readOccurrenceId(intent: Intent): String? = intent.getStringExtra(EXTRA_OCCURRENCE_ID)
  fun readExpectedAt(intent: Intent): Long = intent.getLongExtra(EXTRA_EXPECTED_AT, -1)
  fun readSnoozed(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_SNOOZED, false)
}
