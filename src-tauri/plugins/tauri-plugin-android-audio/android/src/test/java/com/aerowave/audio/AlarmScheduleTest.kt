package com.aerowave.audio

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmScheduleTest {
  private fun alarm(
    hour: Int,
    minute: Int,
    days: List<Int> = emptyList(),
  ) = NativeAlarm(
    id = "wake",
    label = "Wake",
    hour = hour,
    minute = minute,
    days = days,
    enabled = true,
    source = NativeAlarmSource.Folder("content://folder"),
    volume = 0.8f,
    fadeSecs = 20,
    snoozeMins = 10,
    autoStopMins = 30,
    autoSnoozes = 1,
  )

  @Test
  fun weekdaysUseMondayZeroAndOccurrencesAreStrictlyAfter() {
    val zone = ZoneId.of("UTC")
    // 2026-09-07 is Monday.
    val mondayAtSeven = ZonedDateTime.of(2026, 9, 7, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
    assertEquals(
      mondayAtSeven,
      AlarmSchedule.nextAt(alarm(7, 0, listOf(0)), mondayAtSeven - 1, zone),
    )
    assertEquals(
      mondayAtSeven + 7 * 24 * 60 * 60 * 1000L,
      AlarmSchedule.nextAt(alarm(7, 0, listOf(0)), mondayAtSeven, zone),
    )
  }

  @Test
  fun oneShotUsesTheNextLocalOccurrence() {
    val zone = ZoneId.of("UTC")
    val before = ZonedDateTime.of(2026, 9, 7, 6, 59, 0, 0, zone).toInstant().toEpochMilli()
    val after = ZonedDateTime.of(2026, 9, 7, 7, 1, 0, 0, zone).toInstant().toEpochMilli()
    assertEquals(
      ZonedDateTime.of(2026, 9, 7, 7, 0, 0, 0, zone).toInstant().toEpochMilli(),
      AlarmSchedule.nextAt(alarm(7, 0), before, zone),
    )
    assertEquals(
      ZonedDateTime.of(2026, 9, 8, 7, 0, 0, 0, zone).toInstant().toEpochMilli(),
      AlarmSchedule.nextAt(alarm(7, 0), after, zone),
    )
  }

  @Test
  fun springGapRingsAtTheFirstValidLocalInstant() {
    val zone = ZoneId.of("America/New_York")
    val from = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    val actual = AlarmSchedule.nextAt(alarm(2, 30), from, zone)!!
    assertEquals("2026-03-08T03:00-04:00[America/New_York]", Instant.ofEpochMilli(actual).atZone(zone).toString())
  }

  @Test
  fun fallOverlapUsesTheEarlierOffsetOnce() {
    val zone = ZoneId.of("America/New_York")
    val from = ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    val actual = AlarmSchedule.nextAt(alarm(1, 30), from, zone)!!
    assertEquals("2026-11-01T01:30-04:00[America/New_York]", Instant.ofEpochMilli(actual).atZone(zone).toString())
    assertTrue(AlarmSchedule.nextAt(alarm(1, 30), actual, zone)!! > actual + 23 * 60 * 60 * 1000L)
  }

  @Test
  fun missedWindowHasAnInclusiveFifteenMinuteBoundary() {
    val at = 1_000_000L
    assertFalse(AlarmSchedule.isDeliverable(at, at - 1))
    assertTrue(AlarmSchedule.isDeliverable(at, at + AlarmSchedule.MISSED_WINDOW_MS))
    assertFalse(AlarmSchedule.isDeliverable(at, at + AlarmSchedule.MISSED_WINDOW_MS + 1))
  }

  @Test
  fun occurrenceIdentitySeparatesRegularAndSnoozedDeliveries() {
    assertEquals("wake:123:scheduled", AlarmSchedule.occurrenceId("wake", 123, false))
    assertEquals("wake:123:snooze", AlarmSchedule.occurrenceId("wake", 123, true))
  }
}
