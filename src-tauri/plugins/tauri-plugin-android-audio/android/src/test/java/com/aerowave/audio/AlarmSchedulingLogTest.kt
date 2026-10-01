package com.aerowave.audio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmSchedulingLogTest {
  private fun alarm() = NativeAlarm(
    "logging-wake", "Private label", 7, 0, emptyList(), true,
    NativeAlarmSource.Folder("content://private/tree/music"),
    0.8f, 0, 10, 30, 1,
  )

  private fun seed(atMs: Long): ScheduledOccurrence {
    val alarm = alarm()
    val occurrence = ScheduledOccurrence(
      alarm.id, AlarmSchedule.occurrenceId(alarm.id, atMs, false), atMs, false,
    )
    AlarmStateStore.update(RuntimeEnvironment.getApplication()) {
      PersistedAlarmState(initialized = true, alarms = listOf(alarm),
        scheduled = mapOf(alarm.id to occurrence))
    }
    return occurrence
  }

  private fun logs(context: Context = RuntimeEnvironment.getApplication()): List<JSONObject> {
    val ready = CountDownLatch(1)
    var content: ByteArray? = null
    AlarmEventLog.snapshot(context) { snapshot ->
      content = snapshot.jsonl
      ready.countDown()
    }
    assertTrue("The diagnostic snapshot must finish", ready.await(10, TimeUnit.SECONDS))
    return String(content!!, StandardCharsets.UTF_8).lineSequence()
      .filter { it.isNotBlank() }.map(::JSONObject).toList()
  }

  private fun events(name: String, occurrenceId: String? = null): List<JSONObject> =
    logs().filter { event ->
      event.optString("event") == name &&
        (occurrenceId == null || event.optString("occurrence") == fingerprint(occurrenceId))
    }

  @After fun clearPendingRing() {
    AlarmPlaybackService.clearPending()
  }

  @Test fun staleDeliveryRecordsItsRejectionReasonWithoutClaimingAnAlarm() {
    val context = RuntimeEnvironment.getApplication()
    val occurrence = seed(System.currentTimeMillis() + 120_000L)
    val staleId = "stale-delivery"

    assertNull(AndroidAlarmScheduler.claim(
      context, occurrence.alarmId, staleId, occurrence.atMs, false,
    ))

    val event = events("schedule.claim", staleId).last().getJSONObject("fields")
    assertEquals("rejected", event.getString("outcome"))
    assertEquals("stale_occurrence", event.getString("reason"))
    assertNull(AlarmStateStore.snapshot(context).ringing)
    assertEquals(occurrence, AlarmStateStore.snapshot(context).scheduled[occurrence.alarmId])
  }

  @Test fun dueDeliveryRecordsAcceptanceBeforeSourcePlaybackStarts() {
    val context = RuntimeEnvironment.getApplication()
    val occurrence = seed(System.currentTimeMillis() - 1_000L)

    assertNotNull(AndroidAlarmScheduler.claim(
      context, occurrence.alarmId, occurrence.occurrenceId, occurrence.atMs, false,
    ))

    val event = events("schedule.claim", occurrence.occurrenceId).last().getJSONObject("fields")
    assertEquals("accepted", event.getString("outcome"))
    assertEquals("due", event.getString("reason"))
    assertFalse(AlarmStateStore.snapshot(context).alarms.single().enabled)
    val text = logs().joinToString("\n") { it.toString() }
    assertFalse(text.contains("Private label"))
    assertFalse(text.contains("content://private/tree/music"))
  }

  @Test fun invalidDeliveriesRetainDistinctReasonsAndNeverClaimARing() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    val now = System.currentTimeMillis()
    val alarm = alarm()
    val pending = ScheduledOccurrence(alarm.id, "pending-rejections", now + 120_000L, false)
    fun checkReason(reason: String, state: PersistedAlarmState, expectedAt: Long = pending.atMs) {
      AlarmStateStore.update(context) { state }
      assertNull(AndroidAlarmScheduler.claim(context, alarm.id, pending.occurrenceId, expectedAt, false))
      val result = events("schedule.claim", pending.occurrenceId).last().getJSONObject("fields")
      assertEquals(reason, result.getString("reason"))
      assertNull(AlarmStateStore.snapshot(context).ringing)
    }

    val state = PersistedAlarmState(initialized = true, alarms = listOf(alarm),
      scheduled = mapOf(alarm.id to pending))
    checkReason("missing_occurrence", state.copy(scheduled = emptyMap()))
    checkReason("missing_alarm", state.copy(alarms = emptyList()))
    checkReason("stale_deadline", state, pending.atMs + 1)
    checkReason("disabled", state.copy(alarms = listOf(alarm.copy(enabled = false))))
    val expired = pending.copy(atMs = now - 20 * 60_000L)
    checkReason("outside_missed_window", state.copy(scheduled = mapOf(alarm.id to expired)), expired.atMs)
  }

  @Test fun clockAdjustedSnoozeRecordsRearmingInsteadOfClaimingAnEarlyRtcDelivery() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 37)
    val alarm = alarm().copy(enabled = false)
    val snooze = ScheduledOccurrence(
      alarm.id, "logging-early-snooze", System.currentTimeMillis() - 50 * 60_000L, true,
      elapsedDeadlineMs = SystemClock.elapsedRealtime() + 120_000L, bootCount = 37,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(
      initialized = true, alarms = listOf(alarm), snoozes = mapOf(alarm.id to snooze),
    ) }

    assertNull(AndroidAlarmScheduler.claim(context, alarm.id, snooze.occurrenceId, snooze.atMs, true))

    val event = events("schedule.claim", snooze.occurrenceId).last().getJSONObject("fields")
    assertEquals("rearmed", event.getString("outcome"))
    assertEquals("elapsed_snooze_not_due", event.getString("reason"))
    assertTrue(event.getLong("projectedAtMs") > System.currentTimeMillis())
    assertNull(AlarmStateStore.snapshot(context).ringing)
  }

  @Test fun successfulArmingRecordsRequestedAndAcceptedAlarmClockTimes() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val occurrence = seed(System.currentTimeMillis() + 120_000L)

    AndroidAlarmScheduler.replaceRegular(context, occurrence.alarmId)

    val scheduled = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    assertEquals(occurrence.atMs, scheduled.single().triggerAtTime)
    assertTrue(events("schedule.alarm_clock_requested", occurrence.occurrenceId).isNotEmpty())
    val accepted = events("schedule.alarm_clock_set", occurrence.occurrenceId).last()
    assertEquals(occurrence.atMs, accepted.getJSONObject("fields").getLong("expectedAtMs"))
  }

  @Test fun deniedExactAccessRecordsRejectionWithoutReportingAnArmedAlarm() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    val occurrence = seed(System.currentTimeMillis() + 120_000L)

    AndroidAlarmScheduler.replaceRegular(context, occurrence.alarmId)

    assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    assertTrue(events("schedule.alarm_clock_requested", occurrence.occurrenceId).isNotEmpty())
    val rejected = events("schedule.alarm_clock_rejected", occurrence.occurrenceId).last()
    assertEquals("exact_access_denied", rejected.getJSONObject("fields").getString("reason"))
    assertTrue(events("schedule.alarm_clock_set", occurrence.occurrenceId).isEmpty())
  }

  @Test
  @Config(shadows = [RejectingAlarmManager::class])
  fun platformArmingFailureIsLoggedAndCannotEmitAnArmedSuccess() {
    val context = RuntimeEnvironment.getApplication()
    val occurrence = seed(System.currentTimeMillis() + 120_000L)

    assertThrows(SecurityException::class.java) {
      AndroidAlarmScheduler.replaceRegular(context, occurrence.alarmId)
    }

    assertTrue(events("schedule.alarm_clock_requested", occurrence.occurrenceId).isNotEmpty())
    assertTrue(events("schedule.alarm_clock_failed", occurrence.occurrenceId).isNotEmpty())
    assertTrue(events("schedule.alarm_clock_set", occurrence.occurrenceId).isEmpty())
  }

  @Test fun receiverRecordsServiceStartFailureWithoutReportingAnAcceptedStart() {
    val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
      override fun startForegroundService(service: Intent): ComponentName {
        throw IllegalStateException("Private synthetic error content://private/music")
      }
    }
    val occurrence = seed(System.currentTimeMillis() - 1_000L)
    val delivery = Intent(context, AlarmReceiver::class.java)
      .setAction(AndroidAlarmScheduler.ACTION_FIRE)
      .putExtra("alarmId", occurrence.alarmId)
      .putExtra("occurrenceId", occurrence.occurrenceId)
      .putExtra("expectedAt", occurrence.atMs)
      .putExtra("snoozed", false)

    assertThrows(IllegalStateException::class.java) { AlarmReceiver().onReceive(context, delivery) }

    assertTrue(events("receiver.received", occurrence.occurrenceId).isNotEmpty())
    assertTrue(events("receiver.service_start_requested", occurrence.occurrenceId).isNotEmpty())
    assertTrue(events("receiver.service_start_failed", occurrence.occurrenceId).isNotEmpty())
    assertTrue(events("receiver.service_start_accepted", occurrence.occurrenceId).isEmpty())
    assertFalse(logs().joinToString("\n").contains("Private synthetic error"))
  }

  @Implements(AlarmManager::class)
  class RejectingAlarmManager : ShadowAlarmManager() {
    @Implementation
    override fun canScheduleExactAlarms(): Boolean = true

    @Implementation
    override fun setAlarmClock(info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
      throw SecurityException("Synthetic platform arming rejection")
    }
  }
}
