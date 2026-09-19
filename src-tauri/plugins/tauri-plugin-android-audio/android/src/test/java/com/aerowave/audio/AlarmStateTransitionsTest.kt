package com.aerowave.audio

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmStateTransitionsTest {
  private val alarm = NativeAlarm(
    id = "wake",
    label = "Wake",
    hour = 7,
    minute = 0,
    days = listOf(0),
    enabled = true,
    source = NativeAlarmSource.Folder("content://folder"),
    volume = 0.8f,
    fadeSecs = 20,
    snoozeMins = 10,
    autoStopMins = 30,
    autoSnoozes = 2,
  )

  private fun ring(id: String = "ring-a") = RingingRecord(
    alarm = alarm,
    occurrenceId = id,
    trigger = "scheduled",
    startedAtMs = 1_000,
    startedElapsedMs = 1_000,
  )

  @Test
  fun rebootKeepsAnOccurrenceStillInsideTheMissedWindow() {
    val due = ScheduledOccurrence("wake", "due", 10_000, false)
    assertSame(
      due,
      AlarmStateTransitions.nextScheduled(alarm, due, 10_000 + AlarmSchedule.MISSED_WINDOW_MS),
    )
  }

  @Test
  fun rebootDropsExpiredOccurrenceAndComputesTheNextCalendarAlarm() {
    val zone = ZoneId.of("UTC")
    val monday = ZonedDateTime.of(2026, 9, 7, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
    val expired = ScheduledOccurrence("wake", "old", monday, false)
    val next = AlarmStateTransitions.nextScheduled(
      alarm, expired, monday + AlarmSchedule.MISSED_WINDOW_MS + 1, zone,
    )!!
    assertEquals(monday + 7 * 24 * 60 * 60 * 1000L, next.atMs)
    assertFalse(next.snoozed)
  }

  @Test
  fun overlappingSnoozeDeferralPreservesTomorrowRegularSchedule() {
    val tomorrow = ScheduledOccurrence("wake", "tomorrow", 200_000, false)
    val overdueSnooze = ScheduledOccurrence("wake", "snooze", 100_000, true, 1)
    val state = PersistedAlarmState(
      revision = 3,
      alarms = listOf(alarm),
      scheduled = mapOf("wake" to tomorrow),
      snoozes = mapOf("wake" to overdueSnooze),
      ringing = ring("other"),
    )
    val changed = AlarmStateTransitions.deferBehindActive(state, overdueSnooze, 110_000)
    assertEquals(tomorrow, changed.scheduled["wake"])
    assertTrue(changed.snoozes.getValue("wake").snoozed)
    assertEquals(1, changed.snoozes.getValue("wake").autoSnoozesUsed)
  }

  @Test
  fun staleSnoozeActionCannotClearANewerRing() {
    val oldRing = ring("old")
    val newRing = ring("new")
    val state = PersistedAlarmState(alarms = listOf(alarm), ringing = newRing)
    val requested = ScheduledOccurrence("wake", "later", 600_000, true)
    assertSame(state, AlarmStateTransitions.snooze(state, oldRing, requested, true))
    assertEquals("new", state.ringing?.occurrenceId)
    assertNull(state.snoozes["wake"])
  }

  @Test
  fun expiredSnoozeIsConsumedInsteadOfBeingRescheduledInThePast() {
    val expired = ScheduledOccurrence("wake", "late", 1_000, true)
    val state = PersistedAlarmState(
      alarms = listOf(alarm),
      snoozes = mapOf("wake" to expired),
    )
    val changed = AlarmStateTransitions.expire(state, alarm, expired, 1_000_000)
    assertFalse(changed.snoozes.containsKey("wake"))
    assertEquals("A stale alarm delivery was ignored", changed.error)
  }

  @Test
  fun testAndRemovedRingsAreNeverRecoveredAfterBoot() {
    val test = ring().copy(trigger = "test")
    assertNull(AlarmStateTransitions.recoverableRing(
      PersistedAlarmState(alarms = listOf(alarm), ringing = test), 2_000,
    ))
    assertNull(AlarmStateTransitions.recoverableRing(
      PersistedAlarmState(alarms = emptyList(), ringing = ring()), 2_000,
    ))
  }

  @Test
  fun timeChangeRebuildPreservesTheOccurrenceThatIsStillRinging() {
    val active = ring("live")
    val decision = AlarmStateTransitions.ringDuringRebuild(
      PersistedAlarmState(alarms = listOf(alarm), ringing = active),
      nowMs = 2_000,
      activeOccurrenceId = "live",
    )
    assertSame(active, decision.ringing)
    assertNull(decision.recoveredSnooze)
  }

  @Test
  fun rebootRecoveryRetainsHeldExtensionlessHlsMetadata() {
    val interrupted = ring().copy(
      sourceUri = "https://radio.example/live",
      sourceFolder = null,
      sourceKind = "station",
      title = "Station",
      sourceIsHls = true,
    )
    val decision = AlarmStateTransitions.ringDuringRebuild(
      PersistedAlarmState(alarms = listOf(alarm), ringing = interrupted),
      nowMs = 2_000,
      activeOccurrenceId = null,
    )
    assertNull(decision.ringing)
    assertEquals("https://radio.example/live", decision.recoveredSnooze?.heldUri)
    assertNull(decision.recoveredSnooze?.heldFolder)
    assertEquals("station", decision.recoveredSnooze?.heldKind)
    assertTrue(decision.recoveredSnooze?.heldIsHls == true)
  }

  @Test
  fun editingAnotherAlarmPreservesAnAutoDisabledOneShotSnooze() {
    val oneShot = alarm.copy(id = "once", days = emptyList(), enabled = false)
    val editedRepeat = alarm.copy(label = "Edited")
    assertTrue(
      AlarmStateTransitions.cancelledAlarmIds(
        listOf(oneShot, alarm),
        listOf(oneShot, editedRepeat),
      ).isEmpty(),
    )
    assertEquals(
      setOf("wake"),
      AlarmStateTransitions.cancelledAlarmIds(
        listOf(oneShot, alarm),
        listOf(oneShot, alarm.copy(enabled = false)),
      ),
    )
  }
}
