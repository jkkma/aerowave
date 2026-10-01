package com.aerowave.audio

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure persisted-state changes shared by receivers and JVM regression tests. */
internal object AlarmStateTransitions {
  fun restore(
    old: PersistedAlarmState,
    alarms: List<NativeAlarm>,
    stations: List<NativeStation>,
    backupFolder: String?,
    expectedRevision: Long?,
    activeOccurrenceId: String?,
  ): PersistedAlarmState {
    require(expectedRevision != null && expectedRevision == old.revision) {
      "Alarms changed on Android; refresh and try again"
    }
    require(old.ringing == null && old.snoozes.isEmpty() && activeOccurrenceId == null) {
      "Dismiss the active alarm or snooze before restoring a backup"
    }
    require(alarms.map { it.id }.distinct().size == alarms.size) { "Alarm ids must be unique" }
    require(alarms.all { !it.enabled && it.skipDate == null }) {
      "Restored alarms must be off and have no skipped occurrence"
    }
    return old.copy(
      initialized = true,
      revision = old.revision + 1,
      alarms = alarms,
      stations = stations,
      backupFolder = backupFolder,
      scheduled = emptyMap(),
      snoozes = emptyMap(),
      ringing = null,
      error = null,
    )
  }

  fun syncSkipDates(previous: List<NativeAlarm>, incoming: List<NativeAlarm>): List<NativeAlarm> =
    incoming.map { alarm ->
      val old = previous.find { it.id == alarm.id }
      alarm.copy(skipDate = old?.skipDate?.takeIf {
        alarm.enabled && alarm.days.isNotEmpty() && old.enabled &&
          old.days.isNotEmpty() && old.hour == alarm.hour &&
          old.minute == alarm.minute && old.days.toSet() == alarm.days.toSet()
      })
    }

  /** An edit replaces the calendar arm, while a genuine snooze still belongs to its earlier ring. */
  fun syncSchedules(state: PersistedAlarmState, incoming: List<NativeAlarm>): PersistedAlarmState {
    val alarms = syncSkipDates(state.alarms, incoming)
    val previous = state.alarms.associateBy { it.id }
    val unchanged = alarms.mapNotNullTo(mutableSetOf()) { alarm ->
      val before = previous[alarm.id]
      if (alarm.enabled && before != null && before.enabled &&
        before.hour == alarm.hour && before.minute == alarm.minute &&
        before.days.toSet() == alarm.days.toSet()
      ) alarm.id else null
    }
    val cancelled = cancelledAlarmIds(state.alarms, alarms)
    val alarmIds = alarms.mapTo(mutableSetOf()) { it.id }
    return state.copy(
      alarms = alarms,
      scheduled = state.scheduled.filterKeys { it in unchanged },
      snoozes = state.snoozes.filter { (id, occurrence) ->
        id in alarmIds && id !in cancelled &&
          (!occurrence.deferredFromScheduled || id in unchanged)
      },
    )
  }

  fun skippedAt(alarm: NativeAlarm, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (!alarm.enabled || alarm.days.isEmpty()) return null
    val date = alarm.skipDate?.let(LocalDate::parse) ?: return null
    return AlarmSchedule.atOnDate(alarm, date, zone)?.takeIf { it > nowMs }
  }

  fun skipAlarm(
    state: PersistedAlarmState,
    id: String,
    skip: Boolean,
    expectedAtMs: Long,
    expectedRevision: Long?,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
  ): PersistedAlarmState {
    require(expectedRevision == null || expectedRevision == state.revision) {
      "Alarms changed on Android; refresh and try again"
    }
    val alarm = state.alarms.find { it.id == id }
      ?: throw IllegalStateException("That alarm no longer exists")
    require(alarm.enabled && alarm.days.isNotEmpty()) { "Only enabled repeating alarms can be skipped" }
    require(expectedAtMs > nowMs) { "That alarm occurrence has already passed" }
    val date = if (skip) {
      require(skippedAt(alarm, nowMs, zone) == null) { "An upcoming occurrence is already skipped" }
      val target = AlarmSchedule.nextAt(alarm, nowMs, zone)
      require(target == expectedAtMs) { "The next alarm changed; refresh and try again" }
      Instant.ofEpochMilli(expectedAtMs).atZone(zone).toLocalDate()
    } else {
      val skipped = alarm.skipDate?.let(LocalDate::parse)
        ?: throw IllegalStateException("That alarm occurrence is no longer skipped")
      require(AlarmSchedule.atOnDate(alarm, skipped, zone) == expectedAtMs) {
        "The skipped alarm changed; refresh and try again"
      }
      skipped
    }
    val updated = alarm.copy(skipDate = if (skip) date.toString() else null)
    val scheduled = state.scheduled.toMutableMap()
    scheduled.remove(id)
    nextScheduled(updated, state.scheduled[id], nowMs, zone)?.let { scheduled[id] = it }
    return state.copy(
      revision = state.revision + 1,
      alarms = state.alarms.map { if (it.id == id) updated else it },
      scheduled = scheduled,
    )
  }

  fun isSkipped(alarm: NativeAlarm, atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    alarm.days.isNotEmpty() &&
      alarm.skipDate == Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().toString()

  fun cancelledAlarmIds(
    previous: List<NativeAlarm>,
    current: List<NativeAlarm>,
  ): Set<String> = previous.mapNotNullTo(mutableSetOf()) { old ->
    val replacement = current.find { it.id == old.id }
    if (replacement == null || (old.enabled && !replacement.enabled)) old.id else null
  }

  fun nextScheduled(
    alarm: NativeAlarm,
    previous: ScheduledOccurrence?,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
  ): ScheduledOccurrence? {
    if (!alarm.enabled) return null
    if (previous != null && !isSkipped(alarm, previous.atMs, zone) && previous.atMs <= nowMs &&
      AlarmSchedule.isDeliverable(previous.atMs, nowMs)
    ) return previous
    return AlarmSchedule.nextAt(alarm, nowMs, zone)?.let { at ->
      ScheduledOccurrence(
        alarm.id, AlarmSchedule.occurrenceId(alarm.id, at, false), at, false,
      )
    }
  }

  /** Keep an in-boot snooze on elapsed time while AlarmClock uses an RTC timestamp. */
  fun reprojectSnooze(
    occurrence: ScheduledOccurrence,
    nowMs: Long,
    nowElapsedMs: Long,
    bootCount: Int?,
  ): ScheduledOccurrence {
    if (!occurrence.snoozed) return occurrence
    if (bootCount != null && occurrence.bootCount == bootCount &&
      occurrence.elapsedDeadlineMs != null
    ) {
      return occurrence.copy(atMs = nowMs + occurrence.elapsedDeadlineMs - nowElapsedMs)
    }
    // A reboot resets elapsedRealtime. Recover from the durable wall deadline,
    // then anchor the remaining interval to this boot. Old records take this path too.
    val remaining = occurrence.atMs - nowMs
    return if (bootCount != null && remaining > 0) occurrence.copy(
      elapsedDeadlineMs = nowElapsedMs + remaining,
      bootCount = bootCount,
    ) else occurrence.copy(elapsedDeadlineMs = null, bootCount = null)
  }

  fun rebuildSnoozes(
    alarms: List<NativeAlarm>,
    snoozes: Map<String, ScheduledOccurrence>,
    nowMs: Long,
    nowElapsedMs: Long,
    bootCount: Int?,
  ): Map<String, ScheduledOccurrence> {
    val alarmIds = alarms.mapTo(mutableSetOf()) { it.id }
    return buildMap {
      snoozes.forEach { (id, occurrence) ->
        if (id !in alarmIds) return@forEach
        val projected = reprojectSnooze(occurrence, nowMs, nowElapsedMs, bootCount)
        if (projected.atMs > nowMs || AlarmSchedule.isDeliverable(projected.atMs, nowMs)) {
          put(id, projected)
        }
      }
    }
  }

  fun rebuildSchedules(
    state: PersistedAlarmState,
    nowMs: Long,
    nowElapsedMs: Long,
    bootCount: Int?,
    zone: ZoneId = ZoneId.systemDefault(),
  ): PersistedAlarmState {
    val snoozes = rebuildSnoozes(state.alarms, state.snoozes, nowMs, nowElapsedMs, bootCount)
    val alarms = consumeExpiredOneShots(state.alarms, state.scheduled, state.snoozes, snoozes, nowMs)
    val scheduled = alarms.asSequence()
      // A deferred clock is the unconsumed calendar occurrence. Recomputing
      // it during a backward time/zone change could queue that occurrence twice.
      .filter { it.enabled && snoozes[it.id]?.deferredFromScheduled != true }
      .mapNotNull { alarm ->
        nextScheduled(alarm, state.scheduled[alarm.id], nowMs, zone)?.let { alarm.id to it }
      }.toMap()
    return state.copy(alarms = alarms, scheduled = scheduled, snoozes = snoozes)
  }

  private fun consumeExpiredOneShots(
    alarms: List<NativeAlarm>,
    scheduled: Map<String, ScheduledOccurrence>,
    beforeSnoozes: Map<String, ScheduledOccurrence>,
    keptSnoozes: Map<String, ScheduledOccurrence>,
    nowMs: Long,
  ): List<NativeAlarm> {
    val expired = scheduled.filter { (id, occurrence) ->
      keptSnoozes[id]?.deferredFromScheduled != true &&
        occurrence.atMs <= nowMs && !AlarmSchedule.isDeliverable(occurrence.atMs, nowMs)
    }.keys + beforeSnoozes.filter { (id, occurrence) ->
      occurrence.deferredFromScheduled && id !in keptSnoozes
    }.keys
    return alarms.map { alarm ->
      if (alarm.id in expired && alarm.days.isEmpty()) alarm.copy(enabled = false) else alarm
    }
  }

  fun isDeferredScheduled(occurrence: ScheduledOccurrence): Boolean =
    !occurrence.snoozed || occurrence.deferredFromScheduled

  fun claimedAlarm(alarm: NativeAlarm, occurrence: ScheduledOccurrence): NativeAlarm =
    if (alarm.days.isEmpty() && isDeferredScheduled(occurrence))
      alarm.copy(enabled = false) else alarm

  fun regularAfterDelivery(
    state: PersistedAlarmState,
    alarm: NativeAlarm,
    occurrence: ScheduledOccurrence,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
  ): Map<String, ScheduledOccurrence> {
    // Re-enabling a one-shot while its earlier ring is snoozed gives it an
    // independent future arm. Returning from that snooze must not consume it.
    if (!isDeferredScheduled(occurrence)) return state.scheduled
    return state.scheduled.toMutableMap().apply {
      remove(alarm.id)
      if (alarm.enabled && alarm.days.isNotEmpty()) {
        nextScheduled(alarm, null, nowMs, zone)?.let { put(alarm.id, it) }
      }
    }
  }

  fun recoverableRing(state: PersistedAlarmState, nowMs: Long): RingingRecord? =
    state.ringing?.takeIf {
      it.trigger != "test" && state.alarms.any { alarm -> alarm.id == it.alarm.id } &&
        nowMs >= it.startedAtMs && nowMs - it.startedAtMs <= AlarmSchedule.MISSED_WINDOW_MS
    }

  fun ringDuringRebuild(
    state: PersistedAlarmState,
    nowMs: Long,
    activeOccurrenceId: String?,
  ): RebuildRingDecision {
    val ring = state.ringing
    if (ring != null && ring.occurrenceId == activeOccurrenceId) {
      return RebuildRingDecision(ring, null)
    }
    val recovered = recoverableRing(state, nowMs)
    return RebuildRingDecision(
      ringing = null,
      recoveredSnooze = recovered?.let {
        ScheduledOccurrence(
          it.alarm.id,
          AlarmSchedule.occurrenceId(it.alarm.id, nowMs, true),
          nowMs,
          true,
          it.autoSnoozesUsed,
          it.sourceUri,
          it.title,
          it.sourceFolder,
          it.sourceKind,
          it.note,
          it.sourceIsHls,
        )
      },
    )
  }

  fun deferBehindActive(
    state: PersistedAlarmState,
    expected: ScheduledOccurrence,
    nowMs: Long,
    nowElapsedMs: Long? = null,
    bootCount: Int? = null,
  ): PersistedAlarmState {
    val retryAt = maxOf(nowMs + 60_000L, expected.atMs + 60_000L)
    val retry = ScheduledOccurrence(
      expected.alarmId,
      AlarmSchedule.occurrenceId(expected.alarmId, retryAt, true),
      retryAt,
      true,
      expected.autoSnoozesUsed,
      expected.heldUri,
      expected.heldTitle,
      expected.heldFolder,
      expected.heldKind,
      expected.heldNote,
      expected.heldIsHls,
      elapsedDeadlineMs = if (nowElapsedMs != null && bootCount != null)
        nowElapsedMs + retryAt - nowMs else null,
      bootCount = bootCount,
      deferredFromScheduled = expected.deferredFromScheduled || !expected.snoozed,
    )
    return state.copy(
      revision = state.revision + 1,
      scheduled = if (expected.snoozed) state.scheduled else state.scheduled - expected.alarmId,
      snoozes = state.snoozes + (expected.alarmId to retry),
    )
  }

  fun snooze(
    state: PersistedAlarmState,
    ring: RingingRecord,
    occurrence: ScheduledOccurrence,
    exactAllowed: Boolean,
  ): PersistedAlarmState {
    if (state.ringing?.occurrenceId != ring.occurrenceId) return state
    return state.copy(
      revision = state.revision + 1,
      snoozes = state.snoozes + (ring.alarm.id to occurrence),
      ringing = null,
      error = if (exactAllowed) null else
        "Exact alarm access is off; Android cannot deliver the snooze on time",
    )
  }

  fun expire(
    state: PersistedAlarmState,
    alarm: NativeAlarm,
    expected: ScheduledOccurrence,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
  ): PersistedAlarmState {
    val consumed = claimedAlarm(alarm, expected)
    return state.copy(
      revision = state.revision + 1,
      alarms = if (consumed != alarm) state.alarms.map {
        if (it.id == alarm.id) consumed else it
      } else state.alarms,
      scheduled = regularAfterDelivery(state, consumed, expected, nowMs, zone),
      snoozes = if (expected.snoozed) state.snoozes - alarm.id else state.snoozes,
      error = "A stale alarm delivery was ignored",
    )
  }
}

internal data class RebuildRingDecision(
  val ringing: RingingRecord?,
  val recoveredSnooze: ScheduledOccurrence?,
)
