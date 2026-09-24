package com.aerowave.audio

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlarmSkipTest {
  private val utc = ZoneId.of("UTC")
  private val alarm = NativeAlarm(
    id = "wake", label = "Wake", hour = 7, minute = 0,
    days = listOf(0), enabled = true,
    source = NativeAlarmSource.Folder("content://folder"),
    volume = 0.8f, fadeSecs = 20, snoozeMins = 10,
    autoStopMins = 30, autoSnoozes = 1,
  )

  private fun at(year: Int, month: Int, day: Int, hour: Int = 7, zone: ZoneId = utc): Long =
    ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

  private fun beforeMonday() = at(2026, 9, 7) - 60_000

  private fun state(now: Long = beforeMonday()): PersistedAlarmState {
    val scheduled = AlarmStateTransitions.nextScheduled(alarm, null, now, utc)!!
    return PersistedAlarmState(
      initialized = true, revision = 4, alarms = listOf(alarm),
      scheduled = mapOf(alarm.id to scheduled),
      snoozes = mapOf(alarm.id to ScheduledOccurrence(alarm.id, "snooze", now + 120_000, true)),
      ringing = RingingRecord(alarm, "ring", "snooze", now - 60_000, 1),
    )
  }

  @Test fun skipAndUndoChangeOnlyRegularOccurrence() {
    val now = beforeMonday()
    val original = state(now)
    val monday = at(2026, 9, 7)
    val skipped = AlarmStateTransitions.skipAlarm(original, alarm.id, true, monday, 4, now, utc)
    assertEquals("2026-09-07", skipped.alarms.single().skipDate)
    assertEquals(at(2026, 9, 14), skipped.scheduled.getValue(alarm.id).atMs)
    assertEquals(monday, AlarmStateTransitions.skippedAt(skipped.alarms.single(), now, utc))
    assertEquals(5, skipped.revision)
    assertSame(original.snoozes, skipped.snoozes)
    assertSame(original.ringing, skipped.ringing)
    val undone = AlarmStateTransitions.skipAlarm(skipped, alarm.id, false, monday, 5, now, utc)
    assertNull(undone.alarms.single().skipDate)
    assertEquals(monday, undone.scheduled.getValue(alarm.id).atMs)
    assertSame(original.snoozes, undone.snoozes)
  }

  @Test fun staleRevisionTargetAndExpiredUndoAreRejected() {
    val now = beforeMonday()
    val monday = at(2026, 9, 7)
    val initial = state(now)
    assertRejected { AlarmStateTransitions.skipAlarm(initial, alarm.id, true, monday, 3, now, utc) }
    assertRejected { AlarmStateTransitions.skipAlarm(initial, alarm.id, true, monday + 60_000, 4, now, utc) }
    val skipped = AlarmStateTransitions.skipAlarm(initial, alarm.id, true, monday, 4, now, utc)
    assertRejected { AlarmStateTransitions.skipAlarm(skipped, alarm.id, true, at(2026, 9, 14), 5, now, utc) }
    assertRejected { AlarmStateTransitions.skipAlarm(skipped, alarm.id, false, monday, 5, monday, utc) }
    assertNull(AlarmStateTransitions.skippedAt(skipped.alarms.single(), monday, utc))
  }

  @Test fun rebuildAndStaleClaimRejectSkippedDate() {
    val monday = at(2026, 9, 7)
    val skippedAlarm = alarm.copy(skipDate = "2026-09-07")
    val stale = ScheduledOccurrence(alarm.id, "old", monday, false)
    assertTrue(AlarmStateTransitions.isSkipped(skippedAlarm, monday, utc))
    assertEquals(
      at(2026, 9, 14),
      AlarmStateTransitions.nextScheduled(skippedAlarm, stale, monday + 60_000, utc)?.atMs,
    )
  }

  @Test fun skippingNextWeekKeepsAnUnclaimedCatchupDueToday() {
    val monday = at(2026, 9, 7)
    val due = ScheduledOccurrence(alarm.id, "due", monday, false)
    val initial = PersistedAlarmState(
      revision = 1, alarms = listOf(alarm), scheduled = mapOf(alarm.id to due),
    )
    val now = monday + 60_000
    val next = at(2026, 9, 14)
    val skipped = AlarmStateTransitions.skipAlarm(initial, alarm.id, true, next, 1, now, utc)
    assertSame(due, skipped.scheduled[alarm.id])
    assertEquals("2026-09-14", skipped.alarms.single().skipDate)
  }

  @Test fun syncRetainsOnlySameEnabledRecurringCalendar() {
    val prior = alarm.copy(skipDate = "2026-09-07")
    assertEquals(prior.skipDate,
      AlarmStateTransitions.syncSkipDates(listOf(prior), listOf(alarm.copy(label = "New"))).single().skipDate)
    assertEquals(prior.skipDate,
      AlarmStateTransitions.syncSkipDates(
        listOf(prior.copy(days = listOf(0, 2))), listOf(alarm.copy(days = listOf(2, 0))),
      ).single().skipDate)
    assertNull(AlarmStateTransitions.syncSkipDates(listOf(prior), listOf(alarm.copy(hour = 8))).single().skipDate)
    assertNull(AlarmStateTransitions.syncSkipDates(listOf(prior), listOf(alarm.copy(days = listOf(1)))).single().skipDate)
    assertNull(AlarmStateTransitions.syncSkipDates(listOf(prior), listOf(alarm.copy(enabled = false))).single().skipDate)
    assertNull(AlarmStateTransitions.syncSkipDates(emptyList(), listOf(prior)).single().skipDate)
  }

  @Test fun dateRoundTripAndDstUseLocalCalendarDate() {
    val saved = alarm.copy(skipDate = "2026-09-07")
    assertEquals(saved.skipDate, alarmFromJson(saved.toJson()).skipDate)
    val zone = ZoneId.of("America/New_York")
    val spring = alarm.copy(hour = 2, minute = 30, days = listOf(6))
    val from = at(2026, 3, 7, 12, zone)
    val target = AlarmSchedule.nextAt(spring, from, zone)!!
    assertEquals("2026-03-08T03:00-04:00[America/New_York]", Instant.ofEpochMilli(target).atZone(zone).toString())
    val skipped = AlarmStateTransitions.skipAlarm(
      PersistedAlarmState(revision = 1, alarms = listOf(spring)), spring.id,
      true, target, 1, from, zone,
    ).alarms.single()
    assertEquals("2026-03-08", skipped.skipDate)
    assertEquals(target, AlarmStateTransitions.skippedAt(skipped, from, zone))
    assertEquals("2026-03-15", Instant.ofEpochMilli(AlarmSchedule.nextAt(skipped, from, zone)!!)
      .atZone(zone).toLocalDate().toString())
    val overlap = alarm.copy(hour = 1, minute = 30, days = listOf(6), skipDate = "2026-11-01")
    assertEquals("2026-11-01T01:30-04:00[America/New_York]",
      Instant.ofEpochMilli(AlarmStateTransitions.skippedAt(overlap, at(2026, 10, 31, 12, zone), zone)!!)
        .atZone(zone).toString())
    assertFalse(AlarmStateTransitions.isSkipped(overlap, at(2026, 11, 8, 1, zone), zone))
  }

  @Test fun straySkipDateDoesNotSuppressOneShot() {
    val once = alarm.copy(days = emptyList(), skipDate = "2026-09-07")
    val monday = at(2026, 9, 7)
    assertEquals(monday, AlarmSchedule.nextAt(once, monday - 60_000, utc))
    assertFalse(AlarmStateTransitions.isSkipped(once, monday, utc))
    assertNull(AlarmStateTransitions.skippedAt(once, monday - 60_000, utc))
  }

  private fun assertRejected(block: () -> Unit) {
    try {
      block()
      fail("Expected a stale or invalid skip request to be rejected")
    } catch (_: IllegalArgumentException) {
    } catch (_: IllegalStateException) {
    }
  }
}
