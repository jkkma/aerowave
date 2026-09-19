package com.aerowave.audio

import java.time.ZoneId

/** Pure persisted-state changes shared by receivers and JVM regression tests. */
internal object AlarmStateTransitions {
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
    if (previous != null && previous.atMs <= nowMs &&
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
