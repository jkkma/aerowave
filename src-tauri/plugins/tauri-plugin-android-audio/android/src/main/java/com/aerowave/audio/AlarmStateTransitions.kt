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
    val scheduled = state.scheduled.toMutableMap()
    val snoozes = state.snoozes.toMutableMap()
    if (expected.snoozed) {
      snoozes.remove(alarm.id)
    } else {
      scheduled.remove(alarm.id)
      if (alarm.enabled && alarm.days.isNotEmpty()) {
        nextScheduled(alarm, null, nowMs, zone)?.let { scheduled[alarm.id] = it }
      }
    }
    return state.copy(
      revision = state.revision + 1,
      scheduled = scheduled,
      snoozes = snoozes,
      error = "A stale alarm delivery was ignored",
    )
  }
}

internal data class RebuildRingDecision(
  val ringing: RingingRecord?,
  val recoveredSnooze: ScheduledOccurrence?,
)
