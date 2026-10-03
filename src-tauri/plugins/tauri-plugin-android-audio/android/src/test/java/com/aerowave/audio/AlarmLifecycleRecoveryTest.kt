package com.aerowave.audio

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebView
import app.tauri.plugin.Invoke
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmLifecycleRecoveryTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()

  @Before fun prepare() {
    AlarmPlaybackService.clearPending()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 3)
    FailingAlarmManager.remainingFailures = 0
    FailingAlarmManager.rejectAll = false
  }

  @After fun cleanUp() { AlarmPlaybackService.clearPending() }

  private fun alarm(id: String = "clock", enabled: Boolean = true) = NativeAlarm(
    id, "Wake", 7, 0, emptyList(), enabled, NativeAlarmSource.Station("jazz"),
    0.7f, 0, 10, 0, 0,
  )

  private fun futureAlarm(id: String = "future"): NativeAlarm {
    val future = Instant.ofEpochMilli(System.currentTimeMillis() + 5 * 60_000L)
      .atZone(ZoneId.systemDefault())
    return alarm(id).copy(hour = future.hour, minute = future.minute)
  }

  private fun due(alarm: NativeAlarm, at: Long = System.currentTimeMillis() - 1_000L) =
    ScheduledOccurrence(alarm.id, AlarmSchedule.occurrenceId(alarm.id, at, false), at, false)

  private fun clocks(context: Context = this.context) =
    Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
      .filter { it.alarmClockInfo != null }

  private fun clockTimes(context: Context = this.context): Map<String, Long> = clocks(context).associate {
    val intent = Shadows.shadowOf(it.operation).savedIntent
    "${intent.getStringExtra("alarmId")}:${intent.getBooleanExtra("snoozed", false)}" to
      it.alarmClockInfo!!.triggerTime
  }

  private fun fire(occurrence: ScheduledOccurrence) = Intent(context, AlarmReceiver::class.java)
    .setAction(AndroidAlarmScheduler.ACTION_FIRE)
    .putExtra("alarmId", occurrence.alarmId)
    .putExtra("occurrenceId", occurrence.occurrenceId)
    .putExtra("expectedAt", occurrence.atMs)
    .putExtra("snoozed", occurrence.snoozed)

  private class StartContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
    val starts = mutableListOf<Intent>()
    var claimed: RingingRecord? = null
    var reject = false
    override fun startForegroundService(service: Intent): ComponentName {
      starts += service
      claimed = AlarmStateStore.snapshot(this).ringing
      if (reject) throw IllegalStateException("Synthetic foreground start rejection")
      return ComponentName(this, AlarmPlaybackService::class.java)
    }
  }

  class RejectingActivity : Activity() {
    override fun startForegroundService(service: Intent): ComponentName =
      throw IllegalStateException("Synthetic foreground start rejection")
  }

  private class Request(command: String, args: JSONObject) {
    var callback: Long? = null
    var response: String? = null
    val invoke = Invoke(1, command, 2, 3, { callback, data ->
      this.callback = callback
      response = data
    }, args.toString(), ObjectMapper())
    fun assertSuccess() { assertEquals(response, 2L, callback) }
    fun assertFailure() { assertEquals(response, 3L, callback) }
  }

  private fun rejectNextCommitContext(): Context {
    val preferences = context.createDeviceProtectedStorageContext()
      .getSharedPreferences("aerowave_android_alarms", Context.MODE_PRIVATE)
    var reject = true
    val wrapped = object : SharedPreferences by preferences {
      override fun edit(): SharedPreferences.Editor {
        val editor = preferences.edit()
        return object : SharedPreferences.Editor by editor {
          override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            editor.putString(key, value)
            return this
          }
          override fun commit(): Boolean {
            val saved = editor.commit()
            return if (reject) { reject = false; false } else saved
          }
        }
      }
    }
    return object : ContextWrapper(context) {
      override fun createDeviceProtectedStorageContext(): Context = this
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        if (name == "aerowave_android_alarms") wrapped else super.getSharedPreferences(name, mode)
    }
  }

  @Test fun pluginLoadRestoresForceStoppedClocksFromNativeAuthority() {
    val enabled = futureAlarm()
    val disabled = alarm("consumed", false)
    val scheduled = AlarmStateTransitions.nextScheduled(enabled, null, System.currentTimeMillis())!!
    val snooze = ScheduledOccurrence(disabled.id, "earlier-ring-snooze", System.currentTimeMillis() + 120_000L, true,
      heldUri = "content://selected-track", heldKind = "folder")
    val station = NativeStation("jazz", "Jazz", "https://radio.example/live")
    val original = PersistedAlarmState(initialized = true, revision = 14,
      alarms = listOf(enabled, disabled), stations = listOf(station), backupFolder = "content://backup",
      scheduled = mapOf(enabled.id to scheduled), snoozes = mapOf(disabled.id to snooze))
    AlarmStateStore.update(context) { original }
    assertTrue(clocks().isEmpty()) // The OS clocks have gone, while native storage survived.

    val controller = Robolectric.buildActivity(Activity::class.java).setup()
    val webView = WebView(controller.get())
    try {
      AndroidAudioPlugin(controller.get()).load(webView)
      val restored = AlarmStateStore.snapshot(context)
      assertEquals(original.alarms, restored.alarms)
      assertEquals(original.stations, restored.stations)
      assertEquals(original.backupFolder, restored.backupFolder)
      assertEquals(snooze.heldUri, restored.snoozes.getValue(disabled.id).heldUri)
      assertEquals(mapOf("${enabled.id}:false" to scheduled.atMs,
        "${disabled.id}:true" to snooze.atMs), clockTimes())
    } finally { webView.destroy(); controller.pause().stop().destroy() }
  }

  @Test fun startupConsumesMissedOneShotAndRetiresAnAbandonedTestRing() {
    val alarm = alarm()
    val expired = due(alarm, System.currentTimeMillis() - AlarmSchedule.MISSED_WINDOW_MS - 1)
    val test = RingingRecord(alarm, "abandoned-test", "test", System.currentTimeMillis(), 0)
    AlarmStateStore.update(context) { PersistedAlarmState(initialized = true,
      alarms = listOf(alarm), scheduled = mapOf(alarm.id to expired), ringing = test) }

    val repaired = AndroidAlarmScheduler.reconcileOnStartup(context)
    assertNull(repaired.ringing)
    assertFalse(repaired.alarms.single().enabled)
    assertTrue(repaired.scheduled.isEmpty())
    assertTrue(repaired.snoozes.isEmpty())
    assertTrue(clocks().isEmpty())
  }

  @Test fun freshStartupWithDeniedExactAccessStillAllowsTheFirstDefinitionsSync() {
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    assertFalse(AndroidAlarmScheduler.reconcileOnStartup(context).initialized)
    assertNull(AlarmStateStore.snapshot(context).error)
    assertEquals(0L, AlarmStateStore.snapshot(context).revision)
    AlarmReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    val beforeSync = AlarmStateStore.snapshot(context)
    assertFalse(beforeSync.initialized)
    assertNull(beforeSync.error)
    val controller = Robolectric.buildActivity(Activity::class.java).setup()
    try {
      val request = Request("syncAlarms", JSONObject().put("expectedRevision", beforeSync.revision)
        .put("stationsJson", "[]").put("alarmsJson", JSONArray().put(alarm(enabled = false).toJson()).toString()))
      AndroidAudioPlugin(controller.get()).syncAlarms(request.invoke)
      request.assertSuccess()
      assertTrue(AlarmStateStore.snapshot(context).initialized)
      assertNotNull(AlarmStateStore.snapshot(context).error)
      assertFalse(AlarmStateStore.snapshot(context).alarms.single().enabled)
    } finally { controller.pause().stop().destroy() }
  }

  @Test fun failedFirstDefinitionsCommitDoesNotMakeTheFreshStoreLookUnreadable() {
    val alarm = futureAlarm()
    try {
      AndroidAlarmScheduler.rebuild(rejectNextCommitContext(), "sync") { it.copy(
        initialized = true, alarms = listOf(alarm)) }
      fail("The first definitions commit must fail")
    } catch (_: IllegalStateException) { }
    val fresh = AlarmStateStore.snapshot(context)
    assertFalse(fresh.initialized)
    assertNull(fresh.error)
    assertEquals(0L, fresh.revision)
    assertTrue(clocks().isEmpty())
    val retry = AndroidAlarmScheduler.rebuild(context, "sync") { it.copy(initialized = true, alarms = listOf(alarm)) }
    assertTrue(retry.initialized)
    assertNull(retry.error)
    assertEquals(1, clocks().size)
  }

  @Test fun dueClaimClearsAbandonedTestsAndExpiredRealRingsWithoutDeferral() {
    val dueAlarm = alarm("next")
    val prior = alarm("previous", false)
    for (trigger in listOf("test", "scheduled")) {
      AlarmPlaybackService.clearPending()
      val occurrence = due(dueAlarm)
      val phantom = RingingRecord(prior, "phantom-$trigger", trigger,
        System.currentTimeMillis() - AlarmSchedule.MISSED_WINDOW_MS - 1, 0)
      AlarmStateStore.update(context) { PersistedAlarmState(initialized = true,
        alarms = listOf(dueAlarm, prior), scheduled = mapOf(dueAlarm.id to occurrence), ringing = phantom) }

      val accepted = AndroidAlarmScheduler.claim(context, dueAlarm.id,
        occurrence.occurrenceId, occurrence.atMs, false)
      assertEquals(occurrence.occurrenceId, accepted?.occurrenceId)
      assertEquals(occurrence.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
    }
  }

  @Test fun startupRecoversARecentRealRingWithoutReenablingItsOneShot() {
    val alarm = alarm(enabled = false)
    val expiry = System.currentTimeMillis() + 60_000L
    val ring = RingingRecord(alarm, "lost-real", "scheduled", System.currentTimeMillis() - 20_000L, 0,
      sourceUri = "content://chosen", sourceKind = "folder", deliveryExpiresAtMs = expiry)
    AlarmStateStore.update(context) { PersistedAlarmState(initialized = true, alarms = listOf(alarm), ringing = ring) }

    val repaired = AndroidAlarmScheduler.reconcileOnStartup(context)
    assertNull(repaired.ringing)
    assertFalse(repaired.alarms.single().enabled)
    assertTrue(repaired.scheduled.isEmpty())
    val retry = repaired.snoozes.getValue(alarm.id)
    assertEquals("content://chosen", retry.heldUri)
    assertEquals(expiry, retry.expiresAtMs)
    assertEquals(mapOf("${alarm.id}:true" to retry.atMs), clockTimes())
  }

  @Test fun aCalendarClaimAndItsDeferralPreserveAnIndependentSameAlarmSnooze() {
    val alarm = alarm()
    val scheduled = due(alarm)
    val snooze = ScheduledOccurrence(alarm.id, "independent-snooze", System.currentTimeMillis() + 300_000L, true)
    val initial = PersistedAlarmState(initialized = true, alarms = listOf(alarm),
      scheduled = mapOf(alarm.id to scheduled), snoozes = mapOf(alarm.id to snooze))
    AlarmStateStore.update(context) { initial }
    assertNotNull(AndroidAlarmScheduler.claim(context, alarm.id, scheduled.occurrenceId, scheduled.atMs, false))
    assertEquals(snooze, AlarmStateStore.snapshot(context).snoozes[alarm.id])

    AlarmPlaybackService.clearPending()
    val other = RingingRecord(alarm("other"), "live-other", "test", System.currentTimeMillis(), 0)
    AlarmPlaybackService.markPending(other.occurrenceId)
    AlarmStateStore.update(context) { initial.copy(ringing = other) }
    assertNull(AndroidAlarmScheduler.claim(context, alarm.id, scheduled.occurrenceId, scheduled.atMs, false))
    val deferred = AlarmStateStore.snapshot(context)
    assertEquals(snooze, deferred.snoozes[alarm.id])
    assertTrue(deferred.scheduled.getValue(alarm.id).deferredFromScheduled)
    assertFalse(deferred.scheduled.getValue(alarm.id).snoozed)
    assertEquals(scheduled.atMs + AlarmSchedule.MISSED_WINDOW_MS,
      deferred.scheduled.getValue(alarm.id).expiresAtMs)
  }

  @Test fun repeatedDeferralAndClockStepsCannotExtendTheOriginalDeliveryBudget() {
    val alarm = alarm()
    val occurrence = ScheduledOccurrence(alarm.id, "original", 1_000_000L, false)
    val initial = PersistedAlarmState(initialized = true, alarms = listOf(alarm), scheduled = mapOf(alarm.id to occurrence))
    val first = AlarmStateTransitions.deferBehindActive(initial, occurrence, 1_000_000L, 100_000L, 3)
    val retry = first.scheduled.getValue(alarm.id)
    val projected = AlarmStateTransitions.reprojectSnooze(retry, 50_000_000L, 160_000L, 3)
    val second = AlarmStateTransitions.deferBehindActive(first, projected, 50_000_000L, 160_000L, 3)
      .scheduled.getValue(alarm.id)
    assertEquals(retry.expiresElapsedMs, second.expiresElapsedMs)
    val expired = AlarmStateTransitions.reprojectSnooze(second, 1_000_000L,
      100_000L + AlarmSchedule.MISSED_WINDOW_MS + 1, 3)
    assertTrue(AlarmStateTransitions.isExpired(expired, 1_000_000L))
  }

  @Test fun failedPreferenceCommitRestoresPreviousClockTimesAndDefinitions() {
    val oldAlarm = futureAlarm()
    val old = AndroidAlarmScheduler.rebuild(context, "test") { PersistedAlarmState(
      initialized = true, alarms = listOf(oldAlarm)) }
    val previousClocks = clockTimes()
    try {
      AndroidAlarmScheduler.rebuild(rejectNextCommitContext(), "sync") {
        it.copy(alarms = listOf(oldAlarm.copy(enabled = false)))
      }
      fail("The preference commit must reject the edit")
    } catch (_: IllegalStateException) { }

    val retained = AlarmStateStore.snapshot(context)
    assertEquals(old.alarms, retained.alarms)
    assertEquals(old.scheduled, retained.scheduled)
    assertEquals(previousClocks, clockTimes())
    assertNotNull(retained.error)
  }

  @Test fun failedClaimCommitRearmsTheDeliveredClockAndClearsItsPendingMarker() {
    val alarm = alarm()
    val occurrence = due(alarm)
    val old = PersistedAlarmState(initialized = true, alarms = listOf(alarm),
      scheduled = mapOf(alarm.id to occurrence))
    AlarmStateStore.update(context) { old }
    assertTrue(clocks().isEmpty()) // The OS has already consumed the FIRE clock.
    try {
      AndroidAlarmScheduler.claim(rejectNextCommitContext(), alarm.id,
        occurrence.occurrenceId, occurrence.atMs, false)
      fail("The failed durable claim cannot be accepted")
    } catch (_: IllegalStateException) { }
    val retained = AlarmStateStore.snapshot(context)
    assertEquals(old.alarms, retained.alarms)
    assertEquals(old.scheduled, retained.scheduled)
    assertNull(retained.ringing)
    assertNull(AlarmPlaybackService.activeOccurrenceId())
    assertEquals(mapOf("${alarm.id}:false" to occurrence.atMs), clockTimes())
    assertNotNull(retained.error)
  }

  @Test
  @Config(shadows = [FailingAlarmManager::class])
  fun failedPlatformArmRestoresAnExistingClockInsteadOfCancellingIt() {
    val alarm = futureAlarm()
    val old = AndroidAlarmScheduler.rebuild(context, "test") { PersistedAlarmState(initialized = true, alarms = listOf(alarm)) }
    val previousClocks = clockTimes()
    FailingAlarmManager.remainingFailures = 1
    try {
      AndroidAlarmScheduler.rebuild(context, "sync") { it.copy(alarms = listOf(alarm.copy(minute = (alarm.minute + 1) % 60)),
        scheduled = emptyMap()) }
      fail("The platform arm must reject the edit")
    } catch (_: SecurityException) { }
    assertEquals(old.alarms, AlarmStateStore.snapshot(context).alarms)
    assertEquals(old.scheduled, AlarmStateStore.snapshot(context).scheduled)
    assertEquals(previousClocks, clockTimes())
  }

  @Test
  @Config(shadows = [FailingAlarmManager::class])
  fun futureClockArmingFailureCannotSuppressAnAlreadyDurableDueClaim() {
    val startContext = StartContext()
    val dueAlarm = alarm("due")
    val future = futureAlarm()
    val occurrence = due(dueAlarm)
    AlarmStateStore.update(context) { PersistedAlarmState(initialized = true, alarms = listOf(dueAlarm, future),
      scheduled = mapOf(dueAlarm.id to occurrence,
        future.id to AlarmStateTransitions.nextScheduled(future, null, System.currentTimeMillis())!!)) }
    FailingAlarmManager.rejectAll = true
    try {
      AlarmReceiver().onReceive(startContext, fire(occurrence))
      assertEquals(occurrence.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertEquals(AlarmPlaybackService.ACTION_RING, startContext.starts.single().action)
      assertEquals(occurrence.occurrenceId, startContext.starts.single().getStringExtra("occurrenceId"))
      assertNotNull(AlarmStateStore.snapshot(context).error)
    } finally { startContext.starts.forEach(AlarmServiceStarter::complete) }
  }

  @Test fun rejectedForegroundHandoffRetiresTheRingAndArmsABoundedRetry() {
    val startContext = StartContext().apply { reject = true }
    val alarm = alarm()
    val occurrence = due(alarm)
    AlarmStateStore.update(context) { PersistedAlarmState(initialized = true,
      alarms = listOf(alarm), scheduled = mapOf(alarm.id to occurrence)) }
    try {
      AlarmReceiver().onReceive(startContext, fire(occurrence))
      fail("The start must be rejected")
    } catch (_: IllegalStateException) { }
    val recovered = AlarmStateStore.snapshot(context)
    assertNull(recovered.ringing)
    assertNull(AlarmPlaybackService.activeOccurrenceId())
    assertFalse(recovered.alarms.single().enabled)
    val retry = recovered.snoozes.getValue(alarm.id)
    assertTrue(retry.atMs > System.currentTimeMillis())
    val claimed = startContext.claimed!!
    assertEquals(occurrence.atMs + AlarmSchedule.MISSED_WINDOW_MS, claimed.deliveryExpiresAtMs)
    assertEquals(claimed.deliveryExpiresElapsedMs, retry.expiresElapsedMs)
    assertTrue(kotlin.math.abs(retry.expiresAtMs!! - claimed.deliveryExpiresAtMs!!) < 1_000L)
    assertEquals(mapOf("${alarm.id}:true" to retry.atMs), clockTimes())
  }

  @Test fun rejectedTestAlarmCannotLeaveAPhantomOrClobberAnotherPendingRing() {
    val controller = Robolectric.buildActivity(RejectingActivity::class.java).setup()
    try {
      val plugin = AndroidAudioPlugin(controller.get())
      val request = Request("testAlarm", JSONObject().put("alarmJson", alarm().toJson().toString()))
      plugin.testAlarm(request.invoke)
      request.assertFailure()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertNull(AlarmPlaybackService.activeOccurrenceId())

      val other = RingingRecord(alarm("other"), "other-pending", "test", System.currentTimeMillis(), 0)
      AlarmStateStore.update(context) { it.copy(ringing = other) }
      AlarmPlaybackService.markPending(other.occurrenceId)
      val second = Request("testAlarm", JSONObject().put("alarmJson", alarm().toJson().toString()))
      plugin.testAlarm(second.invoke)
      second.assertFailure()
      assertEquals(other, AlarmStateStore.snapshot(context).ringing)
      assertEquals(other.occurrenceId, AlarmPlaybackService.activeOccurrenceId())
    } finally { controller.pause().stop().destroy() }
  }

  @Test fun sourceOnlyRepairArmsCanonicalClocksAndCorruptStorageRejectsOrdinaryDefinitions() {
    val controller = Robolectric.buildActivity(Activity::class.java).setup()
    try {
      val plugin = AndroidAudioPlugin(controller.get())
      val alarm = futureAlarm()
      val disabled = alarm("already-consumed", false)
      val original = PersistedAlarmState(initialized = true, revision = 20,
        alarms = listOf(alarm, disabled), error = "Synthetic operational timer failure")
      AlarmStateStore.update(context) { original }
      val repair = Request("syncAlarms", JSONObject().put("expectedRevision", original.revision)
        .put("stationsJson", "[]").put("backupFolder", "content://new-backup"))
      plugin.syncAlarms(repair.invoke)
      repair.assertSuccess()
      val repaired = AlarmStateStore.snapshot(context)
      assertEquals(original.alarms, repaired.alarms)
      assertEquals("content://new-backup", repaired.backupFolder)
      assertNull(repaired.error)
      assertEquals(1, clocks().size)

      val preferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("aerowave_android_alarms", Context.MODE_PRIVATE)
      val unreadable = "{unreadable native alarm bytes"
      assertTrue(preferences.edit().putString("state", unreadable).commit())
      val replace = Request("syncAlarms", JSONObject().put("expectedRevision", 0)
        .put("stationsJson", "[]").put("alarmsJson", JSONArray().put(alarm.toJson()).toString()))
      plugin.syncAlarms(replace.invoke)
      replace.assertFailure()
      assertEquals(unreadable, preferences.getString("state", null))
      assertFalse(AlarmStateStore.snapshot(context).initialized)
      val test = Request("testAlarm", JSONObject().put("alarmJson", alarm.toJson().toString()))
      plugin.testAlarm(test.invoke)
      test.assertFailure()
      assertEquals(unreadable, preferences.getString("state", null))
    } finally { controller.pause().stop().destroy() }
  }

  @Implements(AlarmManager::class)
  class FailingAlarmManager : ShadowAlarmManager() {
    @Implementation override fun canScheduleExactAlarms(): Boolean = true
    @Implementation override fun setAlarmClock(info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
      if (rejectAll || remainingFailures > 0) {
        remainingFailures = (remainingFailures - 1).coerceAtLeast(0)
        throw SecurityException("Synthetic alarm-clock arming failure")
      }
      super.setAlarmClock(info, operation)
    }
    companion object { var remainingFailures = 0; var rejectAll = false }
  }
}
