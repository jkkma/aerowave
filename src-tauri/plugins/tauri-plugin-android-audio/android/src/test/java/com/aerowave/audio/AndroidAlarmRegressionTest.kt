package com.aerowave.audio

import android.os.SystemClock
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAlarmRegressionTest {
  private fun oneShot() = NativeAlarm(
    id = "deferred-once", label = "Once", hour = 7, minute = 0,
    days = emptyList(), enabled = true,
    source = NativeAlarmSource.Folder("content://folder"),
    volume = 0.3f, fadeSecs = 0, snoozeMins = 10,
    autoStopMins = 1, autoSnoozes = 0,
  )

  @Test fun deferredOneShotIsConsumedWhenItFinallyClaims() {
    val context = RuntimeEnvironment.getApplication()
    val now = System.currentTimeMillis()
    val alarm = oneShot()
    val first = ScheduledOccurrence(alarm.id, "original", now - 90_000, false)
    val other = RingingRecord(alarm.copy(id = "other"), "other-ring", "scheduled", now - 90_000, 1)
    val deferred = AlarmStateTransitions.deferBehindActive(
      PersistedAlarmState(initialized = true, alarms = listOf(alarm),
        scheduled = mapOf(alarm.id to first), ringing = other),
      first, now - 90_000,
    ).copy(ringing = null)
    val retry = deferred.snoozes.getValue(alarm.id)
    AlarmStateStore.update(context) { deferred }

    val ring = AndroidAlarmScheduler.claim(
      context, alarm.id, retry.occurrenceId, retry.atMs, true,
    )
    assertNotNull(ring)
    assertEquals("scheduled", ring!!.trigger)
    assertFalse(ring.alarm.enabled)
    assertFalse(AlarmStateStore.snapshot(context).alarms.single().enabled)
  }

  @Test fun wallClockStepCannotDiscardAnInBootSnooze() {
    val createdWall = 1_000_000L
    val createdElapsed = 100_000L
    val snooze = ScheduledOccurrence(
      "deferred-once", "snooze-1", createdWall + 10 * 60_000L, true,
      elapsedDeadlineMs = createdElapsed + 10 * 60_000L, bootCount = 7,
    )
    val afterClockStep = createdWall + 60 * 60_000L
    val rebuilt = AlarmStateTransitions.rebuildSnoozes(
      listOf(oneShot()), mapOf(snooze.alarmId to snooze),
      afterClockStep, createdElapsed + 60_000L, 7,
    ).getValue(snooze.alarmId)
    assertEquals(snooze.occurrenceId, rebuilt.occurrenceId)
    assertEquals(afterClockStep + 9 * 60_000L, rebuilt.atMs)
  }

  @Test fun backwardClockStepDoesNotExtendAnInBootSnooze() {
    val snooze = ScheduledOccurrence(
      "deferred-once", "snooze-2", 1_600_000L, true,
      elapsedDeadlineMs = 700_000L, bootCount = 7,
    )
    val movedBack = 1_000_000L - 60 * 60_000L
    val rebuilt = AlarmStateTransitions.rebuildSnoozes(
      listOf(oneShot()), mapOf(snooze.alarmId to snooze), movedBack, 160_000L, 7,
    ).getValue(snooze.alarmId)
    assertEquals(movedBack + 9 * 60_000L, rebuilt.atMs)
  }

  @Test fun rebootUsesDurableWallDeadlineThenReanchorsRemainingTime() {
    val original = ScheduledOccurrence(
      "deferred-once", "snooze-3", 1_600_000L, true,
      elapsedDeadlineMs = 700_000L, bootCount = 7,
    )
    val recovered = AlarmStateTransitions.rebuildSnoozes(
      listOf(oneShot()), mapOf(original.alarmId to original),
      1_120_000L, 5_000L, 8,
    ).getValue(original.alarmId)
    assertEquals(1_600_000L, recovered.atMs)
    assertEquals(485_000L, recovered.elapsedDeadlineMs)
    assertEquals(8, recovered.bootCount)
    val later = AlarmStateTransitions.reprojectSnooze(recovered, 5_000_000L, 65_000L, 8)
    assertEquals(5_420_000L, later.atMs)
  }

  @Test fun legacySnoozeRecordCanBeReadAndReanchored() {
    val legacy = org.json.JSONObject()
      .put("alarmId", "deferred-once").put("occurrenceId", "legacy")
      .put("atMs", 1_600_000L).put("snoozed", true)
    val decoded = occurrenceFromJson(legacy)
    assertNull(decoded.elapsedDeadlineMs)
    assertNull(decoded.bootCount)
    val recovered = AlarmStateTransitions.reprojectSnooze(decoded, 1_120_000L, 5_000L, 8)
    assertEquals(485_000L, recovered.elapsedDeadlineMs)
    assertEquals(8, recovered.bootCount)
    assertFalse(recovered.deferredFromScheduled)
  }

  @Test fun earlyRtcDeliveryRearmsTheElapsedSnooze() {
    val context = RuntimeEnvironment.getApplication()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 19)
    val nowWall = System.currentTimeMillis()
    val dueBeforeClockStep = nowWall - 50 * 60_000L
    val snooze = ScheduledOccurrence(
      "deferred-once", "snooze-early", dueBeforeClockStep, true,
      elapsedDeadlineMs = SystemClock.elapsedRealtime() + 9 * 60_000L,
      bootCount = 19,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, alarms = listOf(oneShot()),
      snoozes = mapOf(snooze.alarmId to snooze),
    ) }
    assertNull(AndroidAlarmScheduler.claim(
      context, snooze.alarmId, snooze.occurrenceId, snooze.atMs, true,
    ))
    val pending = AlarmStateStore.snapshot(context).snoozes.getValue(snooze.alarmId)
    assertEquals(snooze.occurrenceId, pending.occurrenceId)
    assertTrue(pending.atMs > System.currentTimeMillis() + 8 * 60_000L)
  }

  @Test fun timeChangeRebuildKeepsTheElapsedSnooze() {
    val context = RuntimeEnvironment.getApplication()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 20)
    val nowWall = System.currentTimeMillis()
    val snooze = ScheduledOccurrence(
      "deferred-once", "snooze-rebuild", nowWall - 50 * 60_000L, true,
      elapsedDeadlineMs = SystemClock.elapsedRealtime() + 9 * 60_000L,
      bootCount = 20,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, alarms = listOf(oneShot()),
      snoozes = mapOf(snooze.alarmId to snooze),
    ) }
    val rebuilt = AndroidAlarmScheduler.rebuild(context, "synthetic TIME_SET")
      .snoozes.getValue(snooze.alarmId)
    assertEquals(snooze.occurrenceId, rebuilt.occurrenceId)
    assertTrue(rebuilt.atMs > System.currentTimeMillis() + 8 * 60_000L)
  }

  @Test fun dueElapsedSnoozeClaimsDespiteStaleWallTimestamp() {
    val context = RuntimeEnvironment.getApplication()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 21)
    val snooze = ScheduledOccurrence(
      "deferred-once", "snooze-due", System.currentTimeMillis() - 50 * 60_000L, true,
      elapsedDeadlineMs = SystemClock.elapsedRealtime(), bootCount = 21,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, alarms = listOf(oneShot().copy(enabled = false)),
      snoozes = mapOf(snooze.alarmId to snooze),
    ) }
    val ring = AndroidAlarmScheduler.claim(
      context, snooze.alarmId, snooze.occurrenceId, snooze.atMs, true,
    )
    assertNotNull(ring)
    assertEquals("snooze", ring!!.trigger)
    assertNull(AlarmStateStore.snapshot(context).snoozes[snooze.alarmId])
  }

  @Test fun expiredDeferredOneShotCannotRearmTomorrow() {
    val context = RuntimeEnvironment.getApplication()
    val now = System.currentTimeMillis()
    val alarm = oneShot()
    val original = ScheduledOccurrence(alarm.id, "original", now - 21 * 60_000L, false)
    val other = RingingRecord(alarm.copy(id = "other"), "other-ring", "scheduled", original.atMs, 1)
    val deferred = AlarmStateTransitions.deferBehindActive(
      PersistedAlarmState(initialized = true, alarms = listOf(alarm),
        scheduled = mapOf(alarm.id to original), ringing = other),
      original, original.atMs,
    ).copy(ringing = null)
    val retry = deferred.snoozes.getValue(alarm.id)
    AlarmStateStore.update(context) { deferred }
    assertNull(AndroidAlarmScheduler.claim(
      context, alarm.id, retry.occurrenceId, retry.atMs, true,
    ))
    val expired = AlarmStateStore.snapshot(context)
    assertFalse(expired.alarms.single().enabled)
    assertNull(expired.snoozes[alarm.id])
    assertNull(AndroidAlarmScheduler.rebuild(context).scheduled[alarm.id])
  }

  @Test fun rebuildConsumesAnExpiredDeferredOneShotBeforeSchedulingTomorrow() {
    val context = RuntimeEnvironment.getApplication()
    val alarm = oneShot()
    val deferred = ScheduledOccurrence(
      alarm.id, "expired-deferred", System.currentTimeMillis() - 20 * 60_000L, true,
      deferredFromScheduled = true,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, alarms = listOf(alarm),
      snoozes = mapOf(alarm.id to deferred),
    ) }
    val rebuilt = AndroidAlarmScheduler.rebuild(context)
    assertFalse(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.snoozes[alarm.id])
    assertNull(rebuilt.scheduled[alarm.id])
  }

  @Test fun reenabledOneShotKeepsItsFutureScheduleAfterAGenuineSnooze() {
    val context = RuntimeEnvironment.getApplication()
    val alarm = oneShot() // Re-enabled while a genuine snooze was pending.
    val future = AlarmStateTransitions.nextScheduled(alarm, null, System.currentTimeMillis())!!
    val snooze = ScheduledOccurrence(
      alarm.id, "genuine-snooze", System.currentTimeMillis() - 30_000L, true,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, alarms = listOf(alarm),
      scheduled = mapOf(alarm.id to future),
      snoozes = mapOf(alarm.id to snooze),
    ) }
    val ring = AndroidAlarmScheduler.claim(
      context, alarm.id, snooze.occurrenceId, snooze.atMs, true,
    )
    assertNotNull(ring)
    assertEquals("snooze", ring!!.trigger)
    val claimed = AlarmStateStore.snapshot(context)
    assertTrue(claimed.alarms.single().enabled)
    assertEquals(future, claimed.scheduled[alarm.id])
    assertNull(claimed.snoozes[alarm.id])
  }
}
