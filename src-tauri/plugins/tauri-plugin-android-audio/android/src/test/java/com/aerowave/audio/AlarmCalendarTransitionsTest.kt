package com.aerowave.audio

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmCalendarTransitionsTest {
  private val utc = ZoneId.of("UTC")
  private val alarm = NativeAlarm(
    id = "calendar-wake", label = "Wake", hour = 7, minute = 0,
    days = emptyList(), enabled = true,
    source = NativeAlarmSource.Folder("content://folder"),
    volume = 0.8f, fadeSecs = 20, snoozeMins = 10,
    autoStopMins = 30, autoSnoozes = 2,
  )
  private val monday = at("2026-09-07T07:00:00Z")

  private fun at(value: String): Long = Instant.parse(value).toEpochMilli()

  private fun regular(atMs: Long, definition: NativeAlarm = alarm) = ScheduledOccurrence(
    definition.id, AlarmSchedule.occurrenceId(definition.id, atMs, false), atMs, false,
  )

  private fun snooze(atMs: Long, deferred: Boolean = false) = ScheduledOccurrence(
    alarm.id, "returning-ring", atMs, true,
    autoSnoozesUsed = 1, heldUri = "content://folder/held",
    heldKind = "folder", heldFolder = "content://folder",
    deferredFromScheduled = deferred,
  )

  private fun state(
    definition: NativeAlarm = alarm,
    due: ScheduledOccurrence? = regular(monday),
    pending: ScheduledOccurrence? = null,
  ) = PersistedAlarmState(
    initialized = true, revision = 4, alarms = listOf(definition),
    scheduled = due?.let { mapOf(definition.id to it) }.orEmpty(),
    snoozes = pending?.let { mapOf(definition.id to it) }.orEmpty(),
  )

  private fun rebuild(
    before: PersistedAlarmState,
    nowMs: Long,
    zone: ZoneId = utc,
    nowElapsedMs: Long = 100_000,
    bootCount: Int? = null,
  ) = AlarmStateTransitions.rebuildSchedules(before, nowMs, nowElapsedMs, bootCount, zone)

  @Test fun overdueClockEditReplacesCatchUpAndKeepsTheGenuineSnooze() {
    val pending = snooze(monday + 10 * 60_000L)
    val before = state(pending = pending)
    val edited = alarm.copy(hour = 8)
    val saved = AlarmStateTransitions.syncSchedules(before, listOf(edited))
    assertNull(saved.scheduled[alarm.id])
    assertSame(pending, saved.snoozes[alarm.id])

    val rebuilt = rebuild(saved, monday + 5 * 60_000L)
    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(monday + 60 * 60_000L, rebuilt.scheduled.getValue(alarm.id).atMs)
    assertEquals(pending, rebuilt.snoozes[alarm.id])
  }

  @Test fun minuteAndRepeatDayEditsReplaceOnlyTheirOwnCalendarArm() {
    val repeating = alarm.copy(days = listOf(0), skipDate = "2026-09-14")
    val other = alarm.copy(id = "other", hour = 9)
    val otherOccurrence = regular(monday + 2 * 60 * 60_000L, other)
    val pending = snooze(monday + 10 * 60_000L)
    val before = state(repeating, pending = pending).copy(
      alarms = listOf(repeating, other),
      scheduled = mapOf(alarm.id to regular(monday), other.id to otherOccurrence),
    )
    val now = monday + 5 * 60_000L
    val minuteEdit = AlarmStateTransitions.syncSchedules(before, listOf(repeating.copy(minute = 30), other))
    assertEquals(monday + 30 * 60_000L, rebuild(minuteEdit, now).scheduled.getValue(alarm.id).atMs)
    assertNull(minuteEdit.alarms.first().skipDate)
    assertEquals(otherOccurrence, minuteEdit.scheduled[other.id])
    assertSame(pending, minuteEdit.snoozes[alarm.id])

    val daysEdit = AlarmStateTransitions.syncSchedules(before, listOf(repeating.copy(days = listOf(1)), other))
    assertEquals(monday + 24 * 60 * 60_000L, rebuild(daysEdit, now).scheduled.getValue(alarm.id).atMs)
    assertNull(daysEdit.alarms.first().skipDate)
    assertEquals(otherOccurrence, daysEdit.scheduled[other.id])
    assertSame(pending, daysEdit.snoozes[alarm.id])
  }

  @Test fun sourceLabelAndReorderedDaysPreserveCatchUpSkipAndSnooze() {
    val repeating = alarm.copy(days = listOf(0, 2), skipDate = "2026-09-09")
    val pending = snooze(monday + 10 * 60_000L)
    val due = regular(monday)
    val before = state(repeating, due, pending)
    val edited = repeating.copy(label = "Edited", source = NativeAlarmSource.Station("station"),
      days = listOf(2, 0), volume = 0.5f)
    val saved = AlarmStateTransitions.syncSchedules(before, listOf(edited))
    val rebuilt = rebuild(saved, monday + 5 * 60_000L)
    assertSame(due, rebuilt.scheduled[alarm.id])
    assertEquals(pending, rebuilt.snoozes[alarm.id])
    assertEquals(repeating.skipDate, rebuilt.alarms.single().skipDate)
    assertEquals(edited.source, rebuilt.alarms.single().source)
    assertEquals("Edited", rebuilt.alarms.single().label)
  }

  @Test fun explicitDisableOrDeletionCancelsBothKindsOfOccurrence() {
    val before = state(pending = snooze(monday + 10 * 60_000L))
    val disabled = AlarmStateTransitions.syncSchedules(before, listOf(alarm.copy(enabled = false)))
    assertTrue(disabled.scheduled.isEmpty())
    assertTrue(disabled.snoozes.isEmpty())
    assertFalse(rebuild(disabled, monday + 5 * 60_000L).alarms.single().enabled)

    val deleted = AlarmStateTransitions.syncSchedules(before, emptyList())
    assertTrue(deleted.alarms.isEmpty())
    assertTrue(deleted.scheduled.isEmpty())
    assertTrue(deleted.snoozes.isEmpty())
  }

  @Test fun reenableDiscardsAnOldExpiredArmAndKeepsAnEarlierGenuineSnooze() {
    val pending = snooze(monday + 30 * 60_000L)
    val before = state(alarm.copy(enabled = false), pending = pending)
    val saved = AlarmStateTransitions.syncSchedules(before, listOf(alarm))
    assertNull(saved.scheduled[alarm.id])
    val rebuilt = rebuild(saved, monday + 20 * 60_000L)
    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(monday + 24 * 60 * 60_000L, rebuilt.scheduled.getValue(alarm.id).atMs)
    assertEquals(pending, rebuilt.snoozes[alarm.id])
  }

  @Test fun anUnrelatedEditPreservesAnAutoDisabledOneShotSnooze() {
    val pending = snooze(monday + 10 * 60_000L)
    val disabled = alarm.copy(enabled = false)
    val other = alarm.copy(id = "other")
    val before = state(disabled, due = null, pending = pending).copy(alarms = listOf(disabled, other))
    val saved = AlarmStateTransitions.syncSchedules(before, listOf(disabled, other.copy(hour = 9)))
    val rebuilt = rebuild(saved, monday + 5 * 60_000L)
    assertFalse(rebuilt.alarms.first().enabled)
    assertNull(rebuilt.scheduled[alarm.id])
    assertEquals(pending, rebuilt.snoozes[alarm.id])
    assertEquals(monday + 2 * 60 * 60_000L, rebuilt.scheduled.getValue(other.id).atMs)
  }

  @Test fun oneShotIsDeliverableAtTheBoundaryAndConsumedImmediatelyAfterIt() {
    val due = regular(monday)
    val pending = snooze(monday + 30 * 60_000L)
    val before = state(due = due, pending = pending)
    val boundary = monday + AlarmSchedule.MISSED_WINDOW_MS
    val retained = rebuild(before, boundary)
    assertTrue(retained.alarms.single().enabled)
    assertSame(due, retained.scheduled[alarm.id])

    val expired = rebuild(before, boundary + 1)
    assertFalse(expired.alarms.single().enabled)
    assertNull(expired.scheduled[alarm.id])
    assertEquals(pending, expired.snoozes[alarm.id])
    assertFalse(rebuild(expired, boundary + 60_000L).alarms.single().enabled)
  }

  @Test fun expiredRepeatingOccurrenceAdvancesInsteadOfDisablingTheRoutine() {
    val repeating = alarm.copy(days = listOf(0), skipDate = "2026-09-14")
    val rebuilt = rebuild(state(repeating), monday + AlarmSchedule.MISSED_WINDOW_MS + 1)
    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(monday + 14 * 24 * 60 * 60_000L, rebuilt.scheduled.getValue(alarm.id).atMs)
  }

  @Test fun sourceOnlySaveDoesNotRearmAnExpiredOneShotButClockEditDoes() {
    val before = state()
    val now = monday + 20 * 60_000L
    val sourceEdit = AlarmStateTransitions.syncSchedules(before,
      listOf(alarm.copy(label = "Edited", source = NativeAlarmSource.Station("station"))))
    val expired = rebuild(sourceEdit, now)
    assertFalse(expired.alarms.single().enabled)
    assertNull(expired.scheduled[alarm.id])

    val clockEdit = AlarmStateTransitions.syncSchedules(before, listOf(alarm.copy(hour = 6)))
    val rearmed = rebuild(clockEdit, now)
    assertTrue(rearmed.alarms.single().enabled)
    assertEquals(at("2026-09-08T06:00:00Z"), rearmed.scheduled.getValue(alarm.id).atMs)
  }

  @Test fun expiredGenuineSnoozeDoesNotConsumeAnIndependentOneShotArm() {
    val future = regular(monday + 60 * 60_000L)
    val before = state(alarm.copy(hour = 8), future, snooze(monday - 20 * 60_000L))
    val rebuilt = rebuild(before, monday)
    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(future, rebuilt.scheduled[alarm.id])
    assertNull(rebuilt.snoozes[alarm.id])
  }

  @Test fun deferredOneShotIsTheOnlyPendingArmUntilItIsConsumed() {
    val pending = snooze(monday + 60_000L, deferred = true)
    val before = state(due = null, pending = pending)
    val rebuilt = rebuild(before, monday)
    assertTrue(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.scheduled[alarm.id])
    assertEquals(pending, rebuilt.snoozes[alarm.id])

    val expired = rebuild(before, pending.atMs + AlarmSchedule.MISSED_WINDOW_MS + 1)
    assertFalse(expired.alarms.single().enabled)
    assertNull(expired.scheduled[alarm.id])
    assertNull(expired.snoozes[alarm.id])
  }

  @Test fun pendingDeferredRoutineCannotDuplicateItsClockAfterABackwardTimeChange() {
    val repeating = alarm.copy(days = listOf(0))
    val pending = snooze(monday + 60_000L, deferred = true).copy(
      elapsedDeadlineMs = 160_000L, bootCount = 7,
    )
    val movedBack = monday - 5 * 60_000L
    val rebuilt = rebuild(state(repeating, due = null, pending = pending), movedBack,
      nowElapsedMs = 100_000L, bootCount = 7)
    assertTrue(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.scheduled[alarm.id])
    assertEquals(movedBack + 60_000L, rebuilt.snoozes.getValue(alarm.id).atMs)
    assertEquals(pending.occurrenceId, rebuilt.snoozes.getValue(alarm.id).occurrenceId)
  }

  @Test fun validDeferredOneShotSupersedesALegacyRegularDuplicateAfterAClockCorrection() {
    val pending = snooze(monday + 60_000L, deferred = true).copy(
      elapsedDeadlineMs = 160_000L, bootCount = 7,
    )
    val movedForward = monday + 2 * 60 * 60_000L
    val rebuilt = rebuild(state(pending = pending), movedForward,
      nowElapsedMs = 100_000L, bootCount = 7)
    assertTrue(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.scheduled[alarm.id])
    assertEquals(movedForward + 60_000L, rebuilt.snoozes.getValue(alarm.id).atMs)
    assertEquals(pending.occurrenceId, rebuilt.snoozes.getValue(alarm.id).occurrenceId)
  }

  @Test fun deferredScheduledRetryIsInvalidatedByAClockEditAndRetainedByASourceEdit() {
    val pending = snooze(monday + 60_000L, deferred = true)
    val before = state(due = null, pending = pending)
    val changedClock = AlarmStateTransitions.syncSchedules(before, listOf(alarm.copy(hour = 8)))
    assertNull(changedClock.snoozes[alarm.id])
    assertEquals(monday + 60 * 60_000L, rebuild(changedClock, monday).scheduled.getValue(alarm.id).atMs)

    val changedSource = AlarmStateTransitions.syncSchedules(before,
      listOf(alarm.copy(label = "Edited", source = NativeAlarmSource.Station("station"))))
    assertSame(pending, changedSource.snoozes[alarm.id])
    assertNull(rebuild(changedSource, monday).scheduled[alarm.id])
  }

  @Test fun deferredRepeatingDeliveryAndExpiryResumeTheCalendar() {
    val repeating = alarm.copy(days = listOf(0))
    val pending = snooze(monday + 60_000L, deferred = true)
    val before = state(repeating, due = null, pending = pending)
    val claimed = AlarmStateTransitions.regularAfterDelivery(before, repeating, pending, pending.atMs, utc)
    assertEquals(monday + 7 * 24 * 60 * 60_000L, claimed.getValue(alarm.id).atMs)
    val expired = AlarmStateTransitions.expire(before, repeating, pending,
      pending.atMs + AlarmSchedule.MISSED_WINDOW_MS + 1, utc)
    assertTrue(expired.alarms.single().enabled)
    assertNull(expired.snoozes[alarm.id])
    assertEquals(claimed, expired.scheduled)
  }

  @Test fun genuineSnoozeDeliveryPreservesTheSkippedRegularCalendar() {
    val repeating = alarm.copy(days = listOf(0), skipDate = "2026-09-14")
    val future = regular(monday + 14 * 24 * 60 * 60_000L)
    val pending = snooze(monday + 60_000L)
    val before = state(repeating, future, pending)
    assertSame(before.scheduled, AlarmStateTransitions.regularAfterDelivery(
      before, AlarmStateTransitions.claimedAlarm(repeating, pending), pending, pending.atMs, utc,
    ))
    val expired = AlarmStateTransitions.expire(before, repeating, pending,
      pending.atMs + AlarmSchedule.MISSED_WINDOW_MS + 1, utc)
    assertSame(before.scheduled, expired.scheduled)
    assertEquals(repeating.skipDate, expired.alarms.single().skipDate)
  }

  @Test fun unchangedTimeZoneRebuildRetainsDeliverableCatchUpAndMovesFutureLocalClock() {
    val shifted = ZoneId.of("America/New_York")
    val saved = AlarmStateTransitions.syncSchedules(state(), listOf(alarm.copy(label = "Edited")))
    val caughtUp = rebuild(saved, monday + 5 * 60_000L, shifted)
    assertEquals(monday, caughtUp.scheduled.getValue(alarm.id).atMs)

    val movedFuture = rebuild(state(), monday - 5 * 60_000L, shifted)
    assertTrue(movedFuture.alarms.single().enabled)
    assertEquals(at("2026-09-07T11:00:00Z"), movedFuture.scheduled.getValue(alarm.id).atMs)
  }

  @Test fun forwardClockCorrectionConsumesExpiredCalendarAndPreservesElapsedSnooze() {
    val pending = snooze(monday + 10 * 60_000L).copy(
      elapsedDeadlineMs = 700_000L, bootCount = 7,
    )
    val movedForward = monday + 2 * 60 * 60_000L
    val rebuilt = rebuild(state(pending = pending), movedForward,
      nowElapsedMs = 160_000L, bootCount = 7)
    assertFalse(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.scheduled[alarm.id])
    assertEquals(movedForward + 9 * 60_000L, rebuilt.snoozes.getValue(alarm.id).atMs)
    assertEquals(pending.occurrenceId, rebuilt.snoozes.getValue(alarm.id).occurrenceId)
  }

  @Test fun freshOneShotWithNoPreviousOccurrenceGetsItsNextCalendarArm() {
    val rebuilt = rebuild(state(due = null), monday + 20 * 60_000L)
    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(monday + 24 * 60 * 60_000L, rebuilt.scheduled.getValue(alarm.id).atMs)
  }
}
