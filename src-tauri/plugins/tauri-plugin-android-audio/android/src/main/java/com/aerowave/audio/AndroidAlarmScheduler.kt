package com.aerowave.audio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

internal object AndroidAlarmScheduler {
  const val ACTION_FIRE = "com.aerowave.audio.action.FIRE_ALARM"
  const val ACTION_PREPARE = "com.aerowave.audio.action.PREPARE_ALARM"
  private const val EXTRA_ALARM_ID = "alarmId"
  private const val EXTRA_OCCURRENCE_ID = "occurrenceId"
  private const val EXTRA_EXPECTED_AT = "expectedAt"
  private const val EXTRA_SNOOZED = "snoozed"

  fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
  }

  /** Recompute wall-clock occurrences after edits, reboot, time or zone changes. */
  fun rebuild(context: Context, reason: String? = null): PersistedAlarmState = logFailures(context, "rebuild") {
    val rebuildReason = rebuildReason(reason)
    AlarmEventLog.record(context, "schedule.rebuild_requested", fields = mapOf("reason" to rebuildReason))
    val before = AlarmStateStore.snapshot(context)
    // A malformed durable record is evidence, not an empty schedule. Keep the
    // raw SharedPreferences entry untouched until an explicit alarm save.
    if (!before.initialized && before.error != null) {
      cancelPreparation(context)
      AlarmPlaybackService.revalidatePreparation(context)
      AlarmEventLog.record(context, "schedule.rebuild_rejected", fields = mapOf(
        "reason" to "unreadable_state", "revision" to before.revision,
      ))
      return@logFailures before
    }
    cancelKnown(context, before)
    val now = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val bootCount = currentBootCount(context)
    val exact = canScheduleExact(context)
    val activeOccurrenceId = AlarmPlaybackService.activeOccurrenceId()
    val changed = AlarmStateStore.update(context) { old ->
      val ringDecision = AlarmStateTransitions.ringDuringRebuild(old, now, activeOccurrenceId)
      val rebuilt = AlarmStateTransitions.rebuildSchedules(old, now, nowElapsed, bootCount)
      val snoozes = rebuilt.snoozes +
        listOfNotNull(ringDecision.recoveredSnooze).associateBy { it.alarmId }
      rebuilt.copy(
        revision = old.revision + 1,
        snoozes = snoozes,
        ringing = ringDecision.ringing,
        error = if (exact) null else "Exact alarm access is off; Android cannot deliver alarms on time",
      )
    }
    val next = (changed.scheduled.values + changed.snoozes.values).minByOrNull { it.atMs }
    AlarmEventLog.record(context, "schedule.rebuilt", next?.occurrenceId, mapOf(
      "reason" to rebuildReason,
      "previousRevision" to before.revision,
      "revision" to changed.revision,
      "nowMs" to now,
      "elapsedMs" to nowElapsed,
      "bootCount" to bootCount,
      "exactAllowed" to exact,
      "alarmCount" to changed.alarms.size,
      "scheduledCount" to changed.scheduled.size,
      "snoozeCount" to changed.snoozes.size,
      "nextAtMs" to next?.atMs,
      "nextSnoozed" to next?.snoozed,
      "ringing" to (changed.ringing != null),
    ))
    if (exact) schedulePersisted(context, changed)
    AlarmPlaybackService.revalidatePreparation(context)
    changed
  }

  fun claim(
    context: Context,
    alarmId: String,
    occurrenceId: String,
    expectedAt: Long,
    snoozed: Boolean,
  ): RingingRecord? = logFailures(context, "claim", occurrenceId) {
    val now = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val bootCount = currentBootCount(context)
    var claimed: RingingRecord? = null
    var outcome = "rejected"
    var reason = "unknown"
    var projectedAt: Long? = null
    val changed = AlarmStateStore.update(context) { old ->
      val stored = (if (snoozed) old.snoozes else old.scheduled)[alarmId]
      val alarm = old.alarms.find { it.id == alarmId }
      if (stored == null) {
        reason = "missing_occurrence"
        return@update old
      }
      if (alarm == null) {
        reason = "missing_alarm"
        return@update old
      }
      if (stored.occurrenceId != occurrenceId) {
        reason = "stale_occurrence"
        return@update old
      }
      if (stored.atMs != expectedAt) {
        reason = "stale_deadline"
        return@update old
      }
      if ((!snoozed || stored.deferredFromScheduled) && !alarm.enabled) {
        reason = "disabled"
        return@update old
      }
      val expected = if (snoozed) AlarmStateTransitions.reprojectSnooze(
        stored, now, nowElapsed, bootCount,
      ) else stored
      projectedAt = expected.atMs
      // An RTC AlarmClock can arrive early after the wall clock jumps forward,
      // before TIME_SET has rebuilt it. Keep the elapsed deadline and rearm it.
      if (snoozed && expected.atMs > now) {
        outcome = "rearmed"
        reason = "elapsed_snooze_not_due"
        return@update if (expected == stored) old else old.copy(
          revision = old.revision + 1,
          snoozes = old.snoozes + (alarmId to expected),
        )
      }
      if (!snoozed && AlarmStateTransitions.isSkipped(alarm, expectedAt)) {
        outcome = "expired"
        reason = "skipped_date"
        return@update AlarmStateTransitions.expire(old, alarm, expected, now)
      }
      if (!AlarmSchedule.isDeliverable(expected.atMs, now)) {
        outcome = "expired"
        reason = "outside_missed_window"
        return@update AlarmStateTransitions.expire(old, alarm, expected, now)
      }
      // If two alarm clocks share a minute, keep the second durable instead
      // of replacing the notification/actions of the one already ringing.
      if (old.ringing != null) {
        outcome = "deferred"
        reason = "another_ring_active"
        return@update AlarmStateTransitions.deferBehindActive(
          old, expected, now, nowElapsed, bootCount,
        )
      }
      val disabled = AlarmStateTransitions.claimedAlarm(alarm, expected)
      val ring = RingingRecord(
        alarm = disabled,
        occurrenceId = occurrenceId,
        trigger = if (snoozed && !AlarmStateTransitions.isDeferredScheduled(expected))
          "snooze" else "scheduled",
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
      outcome = "accepted"
      reason = "due"
      val alarms = old.alarms.map { if (it.id == alarmId) disabled else it }
      old.copy(
        revision = old.revision + 1,
        alarms = alarms,
        scheduled = AlarmStateTransitions.regularAfterDelivery(old, disabled, expected, now),
        snoozes = old.snoozes - alarmId,
        ringing = ring,
        error = null,
      )
    }
    AlarmEventLog.record(context, "schedule.claim", occurrenceId, mapOf(
      "alarmId" to alarmId,
      "outcome" to outcome,
      "reason" to reason,
      "expectedAtMs" to expectedAt,
      "projectedAtMs" to projectedAt,
      "nowMs" to now,
      "elapsedMs" to nowElapsed,
      "bootCount" to bootCount,
      "snoozed" to snoozed,
      "revision" to changed.revision,
      "scheduledCount" to changed.scheduled.size,
      "snoozeCount" to changed.snoozes.size,
    ))
    if (canScheduleExact(context)) schedulePersisted(context, changed)
    claimed
  }

  fun scheduleSnooze(
    context: Context,
    ring: RingingRecord,
    autoSnoozesUsed: Int = ring.autoSnoozesUsed,
  ): PersistedAlarmState = logFailures(context, "snooze", ring.occurrenceId) {
    if (ring.trigger == "test") {
      AlarmEventLog.record(context, "schedule.snooze_rejected", ring.occurrenceId,
        mapOf("reason" to "test_ring"))
      return@logFailures dismiss(context, ring.occurrenceId)
    }
    val delayMs = ring.alarm.snoozeMins.coerceAtLeast(1) * 60_000L
    val at = System.currentTimeMillis() + delayMs
    val bootCount = currentBootCount(context)
    val occurrence = ScheduledOccurrence(
      ring.alarm.id, AlarmSchedule.occurrenceId(ring.alarm.id, at, true), at, true,
      autoSnoozesUsed,
      ring.sourceUri,
      ring.title,
      ring.sourceFolder,
      ring.sourceKind,
      ring.note,
      ring.sourceIsHls,
      elapsedDeadlineMs = bootCount?.let { SystemClock.elapsedRealtime() + delayMs },
      bootCount = bootCount,
    )
    val changed = AlarmStateStore.updateBeforeCommit(context,
      beforeCommit = { before, next ->
        if (before.ringing?.occurrenceId == ring.occurrenceId &&
          next.snoozes[ring.alarm.id]?.occurrenceId == occurrence.occurrenceId) {
          check(canScheduleExact(context)) { "Exact alarm access is off; the alarm is still ringing" }
          check(schedule(context, occurrence)) { "Android could not arm the snooze; the alarm is still ringing" }
        }
      },
      onFailure = { before, next ->
        if (next.snoozes[ring.alarm.id]?.occurrenceId == occurrence.occurrenceId) {
          cancel(context, ring.alarm.id, true)
          before.snoozes[ring.alarm.id]?.let { schedule(context, it) }
        }
      },
    ) { old ->
      AlarmStateTransitions.snooze(old, ring, occurrence, canScheduleExact(context))
    }
    val accepted = changed.snoozes[ring.alarm.id]?.occurrenceId == occurrence.occurrenceId
    AlarmEventLog.record(context, "schedule.snooze", ring.occurrenceId, mapOf(
      "alarmId" to ring.alarm.id,
      "outcome" to if (accepted) "accepted" else "rejected",
      "reason" to if (accepted) "matching_ring" else "stale_occurrence",
      "nextOccurrenceId" to if (accepted) occurrence.occurrenceId else null,
      "nextAtMs" to if (accepted) occurrence.atMs else null,
      "elapsedDeadlineMs" to if (accepted) occurrence.elapsedDeadlineMs else null,
      "bootCount" to bootCount,
      "autoSnoozesUsed" to autoSnoozesUsed,
      "automatic" to (autoSnoozesUsed > ring.autoSnoozesUsed),
      "revision" to changed.revision,
    ))
    updatePreparationAfterCompletion(context, changed)
    changed
  }

  fun dismiss(context: Context, occurrenceId: String): PersistedAlarmState = logFailures(context, "dismiss", occurrenceId) {
    var accepted = false
    val changed = AlarmStateStore.update(context) { old ->
      if (old.ringing?.occurrenceId != occurrenceId) old else {
        accepted = true
        old.copy(revision = old.revision + 1, ringing = null, error = null)
      }
    }
    updatePreparationAfterCompletion(context, changed)
    AlarmEventLog.record(context, "schedule.dismiss", occurrenceId, mapOf(
      "outcome" to if (accepted) "accepted" else "rejected",
      "reason" to if (accepted) "matching_ring" else "stale_occurrence",
      "revision" to changed.revision,
      "snoozeCount" to changed.snoozes.size,
    ))
    changed
  }

  fun cancelAlarm(context: Context, alarmId: String) {
    AlarmEventLog.record(context, "schedule.cancel_requested", fields = mapOf("alarmId" to alarmId))
    cancel(context, alarmId, false)
    cancel(context, alarmId, true)
    schedulePreparation(context, AlarmStateStore.snapshot(context))
    AlarmPlaybackService.revalidatePreparation(context)
  }

  private fun updatePreparationAfterCompletion(context: Context, state: PersistedAlarmState) {
    // Warm-up is optional; an already accepted completion must not become a
    // failed action merely because its next silent preparation was refused.
    try {
      schedulePreparation(context, state)
      AlarmPlaybackService.revalidatePreparation(context)
    } catch (error: RuntimeException) {
      AlarmEventLog.record(context, "schedule.completion_preparation_failed", error = error)
    }
  }

  /** Change only the regular clock; a pending snooze belongs to the current ring. */
  fun replaceRegular(context: Context, alarmId: String) {
    cancel(context, alarmId, false)
    val state = AlarmStateStore.snapshot(context)
    val next = state.scheduled[alarmId]
    next?.let { schedule(context, it) }
    schedulePreparation(context, AlarmStateStore.snapshot(context))
    AlarmPlaybackService.revalidatePreparation(context)
    AlarmEventLog.record(context, "schedule.regular_replaced", next?.occurrenceId, mapOf(
      "alarmId" to alarmId,
      "reason" to "skip_changed",
      "skipped" to (state.alarms.find { it.id == alarmId }?.skipDate != null),
      "nextAtMs" to next?.atMs,
      "revision" to state.revision,
    ))
  }

  private fun schedulePersisted(context: Context, state: PersistedAlarmState) {
    (state.scheduled.values + state.snoozes.values).forEach { schedule(context, it) }
    schedulePreparation(context, state)
  }

  private fun currentBootCount(context: Context): Int? = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
      .takeIf { it >= 0 }
  }.getOrNull()

  private fun cancelKnown(context: Context, state: PersistedAlarmState) {
    cancelPreparation(context)
    (state.scheduled.keys + state.snoozes.keys + state.alarms.map { it.id }).toSet().forEach {
      cancel(context, it, false)
      cancel(context, it, true)
    }
  }

  private fun schedule(context: Context, occurrence: ScheduledOccurrence): Boolean {
    AlarmEventLog.record(context, "schedule.alarm_clock_requested", occurrence.occurrenceId, mapOf(
      "alarmId" to occurrence.alarmId,
      "expectedAtMs" to occurrence.atMs,
      "snoozed" to occurrence.snoozed,
    ))
    if (!canScheduleExact(context)) {
      AlarmEventLog.record(context, "schedule.alarm_clock_rejected", occurrence.occurrenceId,
        mapOf("reason" to "exact_access_denied", "expectedAtMs" to occurrence.atMs))
      return false
    }
    val operation = pendingIntent(context, occurrence, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
    val show = PendingIntent.getActivity(
      context, 70_001, launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    try {
      context.getSystemService(AlarmManager::class.java).setAlarmClock(
        AlarmManager.AlarmClockInfo(occurrence.atMs, show), operation,
      )
      AlarmEventLog.record(context, "schedule.alarm_clock_set", occurrence.occurrenceId, mapOf(
        "alarmId" to occurrence.alarmId,
        "expectedAtMs" to occurrence.atMs,
        "snoozed" to occurrence.snoozed,
        "deferredFromScheduled" to occurrence.deferredFromScheduled,
        "elapsedDeadlineMs" to occurrence.elapsedDeadlineMs,
        "bootCount" to occurrence.bootCount,
      ))
    } catch (error: RuntimeException) {
      AlarmEventLog.recordSystemContext(context, "schedule.alarm_clock_failed", occurrence.occurrenceId,
        mapOf("expectedAtMs" to occurrence.atMs, "snoozed" to occurrence.snoozed), error)
      throw error
    }
    return true
  }

  /** Preparation is best effort; its failure must not change the exact due-time AlarmClock. */
  private fun schedulePreparation(context: Context, state: PersistedAlarmState) {
    cancelPreparation(context)
    if (!canScheduleExact(context)) return
    val nowMs = System.currentTimeMillis()
    val candidate = AlarmPreparation.next(state, nowMs) ?: return
    val operation = preparationPendingIntent(
      context, candidate, PendingIntent.FLAG_UPDATE_CURRENT,
    ) ?: return
    try {
      context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        AlarmPreparation.scheduledAt(candidate, nowMs),
        operation,
      )
      AlarmEventLog.record(context, "schedule.preparation_set", candidate.occurrenceId, mapOf(
        "expectedAtMs" to candidate.atMs,
        "scheduledAtMs" to AlarmPreparation.scheduledAt(candidate, nowMs),
        "snoozed" to candidate.snoozed,
      ))
    } catch (error: RuntimeException) {
      AlarmEventLog.record(context, "schedule.preparation_failed", candidate.occurrenceId,
        mapOf("expectedAtMs" to candidate.atMs, "snoozed" to candidate.snoozed), error)
      Log.w("AerowaveAlarmPrepare", "Station preparation could not be scheduled", error)
    }
  }

  private fun cancelPreparation(context: Context) {
    val operation = preparationPendingIntent(context, null, PendingIntent.FLAG_NO_CREATE) ?: return
    context.getSystemService(AlarmManager::class.java).cancel(operation)
    operation.cancel()
  }

  private fun preparationPendingIntent(
    context: Context,
    candidate: AlarmPreparation.Candidate?,
    lookupFlag: Int,
  ): PendingIntent? {
    val intent = Intent(context, AlarmReceiver::class.java)
      .setAction(ACTION_PREPARE)
      .setData(Uri.Builder().scheme("aerowave").authority("alarm")
        .appendPath("prepare").build())
    if (candidate != null) intent
      .putExtra(EXTRA_ALARM_ID, candidate.alarmId)
      .putExtra(EXTRA_OCCURRENCE_ID, candidate.occurrenceId)
      .putExtra(EXTRA_EXPECTED_AT, candidate.atMs)
      .putExtra(EXTRA_SNOOZED, candidate.snoozed)
    return PendingIntent.getBroadcast(
      context, -70_002, intent,
      lookupFlag or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun cancel(context: Context, alarmId: String, snoozed: Boolean) {
    val placeholder = ScheduledOccurrence(alarmId, "", 0, snoozed)
    val operation = pendingIntent(context, placeholder, PendingIntent.FLAG_NO_CREATE) ?: return
    context.getSystemService(AlarmManager::class.java).cancel(operation)
    operation.cancel()
    AlarmEventLog.record(context, "schedule.alarm_clock_cancelled", fields = mapOf(
      "alarmId" to alarmId, "snoozed" to snoozed,
    ))
  }

  private fun rebuildReason(reason: String?): String = when (reason) {
    null -> "unspecified"
    "sync" -> "alarm_edit"
    Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
    Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot_completed"
    Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
    Intent.ACTION_TIME_CHANGED -> "clock_changed"
    Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
    AlarmManagerPermissionReceiver.ACTION_PERMISSION_CHANGED -> "exact_access_changed"
    else -> "other"
  }

  private inline fun <T> logFailures(
    context: Context,
    operation: String,
    occurrenceId: String? = null,
    block: () -> T,
  ): T = try {
    block()
  } catch (error: RuntimeException) {
    AlarmEventLog.record(context, "schedule.${operation}_failed", occurrenceId, error = error)
    throw error
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
