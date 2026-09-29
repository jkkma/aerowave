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
  fun rebuild(context: Context, reason: String? = null): PersistedAlarmState {
    val before = AlarmStateStore.snapshot(context)
    // A malformed durable record is evidence, not an empty schedule. Keep the
    // raw SharedPreferences entry untouched until an explicit alarm save.
    if (!before.initialized && before.error != null) {
      cancelPreparation(context)
      AlarmPlaybackService.revalidatePreparation(context)
      return before
    }
    cancelKnown(context, before)
    val now = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val bootCount = currentBootCount(context)
    val exact = canScheduleExact(context)
    val activeOccurrenceId = AlarmPlaybackService.activeOccurrenceId()
    val changed = AlarmStateStore.update(context) { old ->
      val ringDecision = AlarmStateTransitions.ringDuringRebuild(old, now, activeOccurrenceId)
      val retainedSnoozes = AlarmStateTransitions.rebuildSnoozes(
        old.alarms, old.snoozes, now, nowElapsed, bootCount,
      )
      val alarms = AlarmStateTransitions.consumeExpiredDeferredOneShots(
        old.alarms, old.snoozes, retainedSnoozes,
      )
      val scheduled = alarms.asSequence()
        .filter { it.enabled }
        .mapNotNull { alarm ->
          AlarmStateTransitions.nextScheduled(alarm, old.scheduled[alarm.id], now)
            ?.let { alarm.id to it }
        }.toMap()
      val snoozes = retainedSnoozes +
        listOfNotNull(ringDecision.recoveredSnooze).associateBy { it.alarmId }
      old.copy(
        revision = old.revision + 1,
        alarms = alarms,
        scheduled = scheduled,
        snoozes = snoozes,
        ringing = ringDecision.ringing,
        error = if (exact) null else "Exact alarm access is off; Android cannot deliver alarms on time",
      )
    }
    if (exact) schedulePersisted(context, changed)
    AlarmPlaybackService.revalidatePreparation(context)
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
    val nowElapsed = SystemClock.elapsedRealtime()
    val bootCount = currentBootCount(context)
    var claimed: RingingRecord? = null
    val changed = AlarmStateStore.update(context) { old ->
      val stored = (if (snoozed) old.snoozes else old.scheduled)[alarmId]
      val alarm = old.alarms.find { it.id == alarmId }
      if (stored == null || alarm == null || stored.occurrenceId != occurrenceId ||
        stored.atMs != expectedAt ||
        ((!snoozed || stored.deferredFromScheduled) && !alarm.enabled)) {
        return@update old
      }
      val expected = if (snoozed) AlarmStateTransitions.reprojectSnooze(
        stored, now, nowElapsed, bootCount,
      ) else stored
      // An RTC AlarmClock can arrive early after the wall clock jumps forward,
      // before TIME_SET has rebuilt it. Keep the elapsed deadline and rearm it.
      if (snoozed && expected.atMs > now) {
        return@update if (expected == stored) old else old.copy(
          revision = old.revision + 1,
          snoozes = old.snoozes + (alarmId to expected),
        )
      }
      if (!snoozed && AlarmStateTransitions.isSkipped(alarm, expectedAt)) {
        return@update AlarmStateTransitions.expire(old, alarm, expected, now)
      }
      if (!AlarmSchedule.isDeliverable(expected.atMs, now)) {
        return@update AlarmStateTransitions.expire(old, alarm, expected, now)
      }
      // If two alarm clocks share a minute, keep the second durable instead
      // of replacing the notification/actions of the one already ringing.
      if (old.ringing != null) {
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
    val changed = AlarmStateStore.update(context) { old ->
      AlarmStateTransitions.snooze(old, ring, occurrence, canScheduleExact(context))
    }
    if (canScheduleExact(context) &&
      changed.snoozes[ring.alarm.id]?.occurrenceId == occurrence.occurrenceId
    ) schedule(context, occurrence)
    schedulePreparation(context, changed)
    AlarmPlaybackService.revalidatePreparation(context)
    return changed
  }

  fun dismiss(context: Context, occurrenceId: String): PersistedAlarmState {
    val changed = AlarmStateStore.update(context) { old ->
      if (old.ringing?.occurrenceId != occurrenceId) old else old.copy(
        revision = old.revision + 1,
        ringing = null,
        error = null,
      )
    }
    schedulePreparation(context, changed)
    return changed
  }

  fun cancelAlarm(context: Context, alarmId: String) {
    cancel(context, alarmId, false)
    cancel(context, alarmId, true)
    schedulePreparation(context, AlarmStateStore.snapshot(context))
    AlarmPlaybackService.revalidatePreparation(context)
  }

  /** Change only the regular clock; a pending snooze belongs to the current ring. */
  fun replaceRegular(context: Context, alarmId: String) {
    cancel(context, alarmId, false)
    AlarmStateStore.snapshot(context).scheduled[alarmId]?.let { schedule(context, it) }
    schedulePreparation(context, AlarmStateStore.snapshot(context))
    AlarmPlaybackService.revalidatePreparation(context)
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
    } catch (error: RuntimeException) {
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
