package com.aerowave.audio

import android.app.AlarmManager
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmCalendarSchedulerTest {
  private fun alarm(atMs: Long = System.currentTimeMillis()) = NativeAlarm(
    id = "calendar-wake", label = "Wake",
    hour = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).hour,
    minute = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).minute,
    days = emptyList(), enabled = true,
    source = NativeAlarmSource.Folder("content://folder"),
    volume = 0.8f, fadeSecs = 20, snoozeMins = 10,
    autoStopMins = 30, autoSnoozes = 2,
  )

  private fun seed(
    definition: NativeAlarm,
    regular: ScheduledOccurrence? = null,
    snooze: ScheduledOccurrence? = null,
  ) {
    AlarmStateStore.update(RuntimeEnvironment.getApplication()) { PersistedAlarmState(
      initialized = true, revision = 4, alarms = listOf(definition),
      scheduled = regular?.let { mapOf(definition.id to it) }.orEmpty(),
      snoozes = snooze?.let { mapOf(definition.id to it) }.orEmpty(),
    ) }
  }

  private fun armedIntents(): List<Intent> {
    val manager = RuntimeEnvironment.getApplication().getSystemService(AlarmManager::class.java)
    return Shadows.shadowOf(manager).scheduledAlarms.map { Shadows.shadowOf(it.operation).savedIntent }
  }

  @Before fun allowExactClocks() {
    AlarmPlaybackService.clearPending()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
  }

  @After fun clearPendingRing() {
    AlarmPlaybackService.clearPending()
  }

  @Test fun claimingAGenuineSnoozePreservesAndArmsTheReenabledOneShot() {
    val context = RuntimeEnvironment.getApplication()
    val definition = alarm()
    val future = AlarmStateTransitions.nextScheduled(definition, null, System.currentTimeMillis())!!
    val snooze = ScheduledOccurrence(definition.id, "genuine-snooze", System.currentTimeMillis() - 30_000L, true)
    seed(definition, future, snooze)

    val ring = AndroidAlarmScheduler.claim(context, definition.id, snooze.occurrenceId, snooze.atMs, true)

    assertNotNull(ring)
    assertEquals("snooze", ring!!.trigger)
    val after = AlarmStateStore.snapshot(context)
    assertTrue(after.alarms.single().enabled)
    assertEquals(future, after.scheduled[definition.id])
    assertNull(after.snoozes[definition.id])
    val intent = armedIntents().single()
    assertFalse(intent.getBooleanExtra("snoozed", true))
    assertEquals(future.occurrenceId, intent.getStringExtra("occurrenceId"))
    assertEquals(future.atMs, intent.getLongExtra("expectedAt", -1))
  }

  @Test fun rebuildConsumesAnExpiredRegularOneShotAndStillArmsItsGenuineSnooze() {
    val context = RuntimeEnvironment.getApplication()
    val dueAt = System.currentTimeMillis() - 20 * 60_000L
    val definition = alarm(dueAt)
    val expired = ScheduledOccurrence(definition.id, "expired-regular", dueAt, false)
    val snooze = ScheduledOccurrence(definition.id, "valid-snooze", System.currentTimeMillis() + 10 * 60_000L, true)
    seed(definition, expired, snooze)

    val rebuilt = AndroidAlarmScheduler.rebuild(context, Intent.ACTION_BOOT_COMPLETED)

    assertFalse(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.scheduled[definition.id])
    assertEquals(snooze.occurrenceId, rebuilt.snoozes[definition.id]?.occurrenceId)
    assertEquals(snooze.atMs, rebuilt.snoozes[definition.id]?.atMs)
    val intent = armedIntents().single()
    assertTrue(intent.getBooleanExtra("snoozed", false))
    assertEquals(snooze.occurrenceId, intent.getStringExtra("occurrenceId"))
    val again = AndroidAlarmScheduler.rebuild(context, Intent.ACTION_MY_PACKAGE_REPLACED)
    assertFalse(again.alarms.single().enabled)
    assertNull(again.scheduled[definition.id])
  }

  @Test fun rebuildKeepsADeliverableRegularOneShotOnItsOriginalClock() {
    val context = RuntimeEnvironment.getApplication()
    val dueAt = System.currentTimeMillis() - 5 * 60_000L
    val definition = alarm(dueAt)
    val due = ScheduledOccurrence(definition.id, "deliverable-regular", dueAt, false)
    seed(definition, due)

    val rebuilt = AndroidAlarmScheduler.rebuild(context, Intent.ACTION_TIME_CHANGED)

    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(due, rebuilt.scheduled[definition.id])
    assertEquals(due.occurrenceId, armedIntents().single().getStringExtra("occurrenceId"))
  }

  @Test fun savingAnOverdueClockEditArmsTheNewTimeAndRejectsTheOldDelivery() {
    val context = RuntimeEnvironment.getApplication()
    val now = System.currentTimeMillis()
    val oldAt = now - 5 * 60_000L
    val definition = alarm(oldAt)
    val obsolete = ScheduledOccurrence(definition.id, "old-clock", oldAt, false)
    val snooze = ScheduledOccurrence(definition.id, "genuine-snooze", now + 10 * 60_000L, true)
    seed(definition, obsolete, snooze)
    val edited = definition.copy(hour = (definition.hour + 1) % 24)
    // This is the same durable transition invoked by AndroidAudioPlugin.syncAlarms.
    AlarmStateStore.update(context) { old ->
      AlarmStateTransitions.syncSchedules(old, listOf(edited)).copy(revision = old.revision + 1)
    }
    val intendedAt = AlarmSchedule.nextAt(edited, now)!!

    val rebuilt = AndroidAlarmScheduler.rebuild(context, "sync")

    assertTrue(rebuilt.alarms.single().enabled)
    assertEquals(intendedAt, rebuilt.scheduled.getValue(definition.id).atMs)
    assertEquals(snooze.occurrenceId, rebuilt.snoozes[definition.id]?.occurrenceId)
    assertEquals(2, armedIntents().size)
    assertNull(AndroidAlarmScheduler.claim(context, definition.id, obsolete.occurrenceId, obsolete.atMs, false))
    val afterStale = AlarmStateStore.snapshot(context)
    assertNull(afterStale.ringing)
    assertEquals(intendedAt, afterStale.scheduled.getValue(definition.id).atMs)
  }

  @Test fun rebuildingAPendingDeferredOneShotArmsOnlyItsRetryAndClaimConsumesIt() {
    val context = RuntimeEnvironment.getApplication()
    val definition = alarm()
    val retry = ScheduledOccurrence(definition.id, "deferred-once", System.currentTimeMillis() - 30_000L, true,
      deferredFromScheduled = true)
    seed(definition, snooze = retry)

    val rebuilt = AndroidAlarmScheduler.rebuild(context, Intent.ACTION_TIMEZONE_CHANGED)

    assertTrue(rebuilt.alarms.single().enabled)
    assertNull(rebuilt.scheduled[definition.id])
    assertEquals(retry.occurrenceId, rebuilt.snoozes[definition.id]?.occurrenceId)
    assertEquals(retry.occurrenceId, armedIntents().single().getStringExtra("occurrenceId"))
    val ring = AndroidAlarmScheduler.claim(context, definition.id, retry.occurrenceId, retry.atMs, true)
    assertNotNull(ring)
    assertEquals("scheduled", ring!!.trigger)
    val after = AlarmStateStore.snapshot(context)
    assertFalse(after.alarms.single().enabled)
    assertNull(after.scheduled[definition.id])
    assertNull(after.snoozes[definition.id])
  }

  @Test fun claimingADeferredRepeatingAlarmResumesItsRegularClock() {
    val context = RuntimeEnvironment.getApplication()
    val definition = alarm().copy(days = (0..6).toList())
    val retry = ScheduledOccurrence(definition.id, "deferred-repeat", System.currentTimeMillis() - 30_000L, true,
      deferredFromScheduled = true)
    seed(definition, snooze = retry)
    assertNull(AndroidAlarmScheduler.rebuild(context).scheduled[definition.id])

    val ring = AndroidAlarmScheduler.claim(context, definition.id, retry.occurrenceId, retry.atMs, true)

    assertNotNull(ring)
    assertEquals("scheduled", ring!!.trigger)
    val after = AlarmStateStore.snapshot(context)
    assertTrue(after.alarms.single().enabled)
    assertNull(after.snoozes[definition.id])
    val future = after.scheduled.getValue(definition.id)
    assertEquals(AlarmSchedule.nextAt(definition, System.currentTimeMillis()), future.atMs)
    val regularIntent = armedIntents().filter { !it.getBooleanExtra("snoozed", false) }.single()
    assertEquals(future.occurrenceId, regularIntent.getStringExtra("occurrenceId"))
  }

  @Test fun expiringADeferredRepeatingDeliveryArmsItsNextRegularClock() {
    val context = RuntimeEnvironment.getApplication()
    val definition = alarm().copy(days = (0..6).toList())
    val expired = ScheduledOccurrence(definition.id, "expired-deferred-repeat",
      System.currentTimeMillis() - 20 * 60_000L, true, deferredFromScheduled = true)
    seed(definition, snooze = expired)

    assertNull(AndroidAlarmScheduler.claim(context, definition.id, expired.occurrenceId, expired.atMs, true))

    val after = AlarmStateStore.snapshot(context)
    assertTrue(after.alarms.single().enabled)
    assertNull(after.snoozes[definition.id])
    val future = after.scheduled.getValue(definition.id)
    assertEquals(AlarmSchedule.nextAt(definition, System.currentTimeMillis()), future.atMs)
    assertEquals(future.occurrenceId, armedIntents().single().getStringExtra("occurrenceId"))
  }
}
