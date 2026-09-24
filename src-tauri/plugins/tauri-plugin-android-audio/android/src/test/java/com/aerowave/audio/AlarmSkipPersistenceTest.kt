package com.aerowave.audio

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmSkipPersistenceTest {
  @Test fun skippedDateSurvivesDurableStateReloadAndUndo() {
    val context = RuntimeEnvironment.getApplication()
    val alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = listOf(0), enabled = true,
      source = NativeAlarmSource.Folder("content://folder"),
      volume = 0.8f, fadeSecs = 20, snoozeMins = 10,
      autoStopMins = 30, autoSnoozes = 1, skipDate = "2026-09-07",
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, revision = 8, alarms = listOf(alarm),
    ) }
    assertEquals("2026-09-07", AlarmStateStore.snapshot(context).alarms.single().skipDate)
    AlarmStateStore.update(context) { old -> old.copy(
      revision = old.revision + 1,
      alarms = old.alarms.map { it.copy(skipDate = null) },
    ) }
    assertNull(AlarmStateStore.snapshot(context).alarms.single().skipDate)
    assertEquals(9, AlarmStateStore.snapshot(context).revision)
  }

  @Test fun stalePendingIntentCannotClaimASkippedDate() {
    val context = RuntimeEnvironment.getApplication()
    val atMs = System.currentTimeMillis() - 30_000
    val local = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
    val alarm = NativeAlarm(
      id = "stale", label = "Stale", hour = local.hour, minute = local.minute,
      days = listOf(local.dayOfWeek.value - 1), enabled = true,
      source = NativeAlarmSource.Folder("content://folder"),
      volume = 0.8f, fadeSecs = 20, snoozeMins = 10,
      autoStopMins = 30, autoSnoozes = 1,
      skipDate = local.toLocalDate().toString(),
    )
    val occurrence = ScheduledOccurrence(
      alarm.id, AlarmSchedule.occurrenceId(alarm.id, atMs, false), atMs, false,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, revision = 1, alarms = listOf(alarm),
      scheduled = mapOf(alarm.id to occurrence),
    ) }
    assertNull(AndroidAlarmScheduler.claim(context, alarm.id, occurrence.occurrenceId, atMs, false))
    val changed = AlarmStateStore.snapshot(context)
    assertNull(changed.ringing)
    assertTrue(changed.scheduled.getValue(alarm.id).atMs > System.currentTimeMillis())
  }
}
