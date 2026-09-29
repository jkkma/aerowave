package com.aerowave.audio

import android.app.AlarmManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmPreparationSchedulerTest {
  private fun alarm() = NativeAlarm(
    "wake", "Wake", 7, 0, emptyList(), true,
    NativeAlarmSource.Station("jazz"),
    0.8f, 20, 10, 30, 1,
  )

  private fun occurrence(atMs: Long) = ScheduledOccurrence(
    "wake", AlarmSchedule.occurrenceId("wake", atMs, false), atMs, false,
  )

  private fun seed(atMs: Long) {
    val context = RuntimeEnvironment.getApplication()
    AlarmStateStore.update(context) {
      PersistedAlarmState(
        initialized = true, alarms = listOf(alarm()),
        stations = listOf(NativeStation("jazz", "Jazz Fusion", "http://example.com/live")),
        scheduled = mapOf("wake" to occurrence(atMs)),
      )
    }
  }

  @Test fun preparationHasDistinctBroadcastAndLeavesTheOneShotAlarmClockUnconsumed() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val due = System.currentTimeMillis() + 120_000
    seed(due)

    AndroidAlarmScheduler.replaceRegular(context, "wake")

    val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    assertEquals(2, alarms.size)
    val fire = alarms.single { it.alarmClockInfo != null }
    val prepare = alarms.single { it.alarmClockInfo == null }
    assertEquals(due, fire.triggerAtTime)
    assertEquals(due - 60_000, prepare.triggerAtTime)
    assertEquals(AlarmManager.RTC_WAKEUP, prepare.type)
    assertTrue(prepare.allowWhileIdle)
    assertNotEquals(fire.operation, prepare.operation)
    assertNotEquals(
      Shadows.shadowOf(fire.operation).requestCode,
      Shadows.shadowOf(prepare.operation).requestCode,
    )
    assertEquals(
      AndroidAlarmScheduler.ACTION_FIRE,
      Shadows.shadowOf(fire.operation).savedIntent.action,
    )
    assertEquals(
      AndroidAlarmScheduler.ACTION_PREPARE,
      Shadows.shadowOf(prepare.operation).savedIntent.action,
    )
    assertTrue(AlarmStateStore.snapshot(context).alarms.single().enabled)
    assertEquals(due, AlarmStateStore.snapshot(context).scheduled.getValue("wake").atMs)
    assertNull(AlarmStateStore.snapshot(context).ringing)
  }

  @Test fun replacingAnOccurrenceCancelsItsOldPreparationAndKeepsTheNewFireTime() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val initialDue = System.currentTimeMillis() + 120_000
    seed(initialDue)
    AndroidAlarmScheduler.replaceRegular(context, "wake")
    val revisedDue = initialDue + 120_000
    AlarmStateStore.update(context) { old -> old.copy(
      revision = old.revision + 1,
      scheduled = mapOf("wake" to occurrence(revisedDue)),
    ) }

    AndroidAlarmScheduler.replaceRegular(context, "wake")

    val alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    assertEquals(2, alarms.size)
    assertEquals(revisedDue, alarms.single { it.alarmClockInfo != null }.triggerAtTime)
    assertEquals(revisedDue - 60_000, alarms.single { it.alarmClockInfo == null }.triggerAtTime)
    assertFalse(alarms.any { it.triggerAtTime == initialDue || it.triggerAtTime == initialDue - 60_000 })
    assertNull(AlarmPreparation.candidate(context, "wake", occurrence(initialDue).occurrenceId,
      initialDue, false))
    assertTrue(AlarmStateStore.snapshot(context).alarms.single().enabled)
  }

  @Test fun dismissingTheCurrentRingRearmsPreparationForTheNextAlarm() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val firstDue = System.currentTimeMillis() + 120_000
    val nextDue = firstDue + 90_000
    seed(firstDue)
    val nextAlarm = alarm().copy(id = "later")
    val nextOccurrence = ScheduledOccurrence(
      "later", AlarmSchedule.occurrenceId("later", nextDue, false), nextDue, false,
    )
    AlarmStateStore.update(context) { old -> old.copy(
      alarms = old.alarms + nextAlarm,
      scheduled = old.scheduled + ("later" to nextOccurrence),
    ) }
    AndroidAlarmScheduler.replaceRegular(context, "wake")
    AndroidAlarmScheduler.replaceRegular(context, "later")

    val firstOccurrence = occurrence(firstDue)
    val ring = RingingRecord(
      alarm = alarm(), occurrenceId = firstOccurrence.occurrenceId,
      trigger = "scheduled", startedAtMs = firstDue, startedElapsedMs = 5_000,
    )
    AlarmStateStore.update(context) { old -> old.copy(
      scheduled = old.scheduled - "wake", ringing = ring,
    ) }
    AndroidAlarmScheduler.cancelAlarm(context, "wake")
    var alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    assertEquals(1, alarms.size)
    assertEquals(nextDue, alarms.single().triggerAtTime)

    AndroidAlarmScheduler.dismiss(context, firstOccurrence.occurrenceId)

    alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    assertEquals(2, alarms.size)
    assertEquals(nextDue, alarms.single { it.alarmClockInfo != null }.triggerAtTime)
    val prepare = alarms.single { it.alarmClockInfo == null }
    assertEquals(nextDue - AlarmPreparation.LEAD_MS, prepare.triggerAtTime)
    assertEquals(nextOccurrence.occurrenceId,
      AndroidAlarmScheduler.readOccurrenceId(Shadows.shadowOf(prepare.operation).savedIntent))
    assertNull(AlarmStateStore.snapshot(context).ringing)
  }

  @Test fun revokedExactAlarmAccessInvalidatesAnExistingPreparation() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val due = System.currentTimeMillis() + 45_000
    seed(due)
    val occurrence = occurrence(due)
    val candidate = AlarmPreparation.candidate(
      context, "wake", occurrence.occurrenceId, due, false,
    )!!
    assertTrue(AlarmPreparation.stillCurrent(context, candidate))

    ShadowAlarmManager.setCanScheduleExactAlarms(false)

    assertFalse(AlarmPreparation.stillCurrent(context, candidate))
    assertNull(AlarmPreparation.candidate(
      context, "wake", occurrence.occurrenceId, due, false,
    ))
    assertTrue(AlarmStateStore.snapshot(context).alarms.single().enabled)
  }

  @Test fun revokedExactAlarmAccessEndsDueTimeGrace() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val due = System.currentTimeMillis() - 1_000
    seed(due)
    val candidate = AlarmPreparation.Candidate(
      "wake", occurrence(due).occurrenceId, due, false,
      "jazz", "Jazz Fusion", "http://example.com/live", null, false,
    )
    assertTrue(AlarmPreparation.eligibleDuringClaim(context, candidate))

    ShadowAlarmManager.setCanScheduleExactAlarms(false)

    assertFalse(AlarmPreparation.eligibleDuringClaim(context, candidate))
  }
}
