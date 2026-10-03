package com.aerowave.audio

import android.app.AlarmManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmServiceStarterTest {
  private class StartContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
    val starts = mutableListOf<Intent>()
    var reject = false

    override fun startForegroundService(service: Intent): ComponentName {
      assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
      starts += service
      if (reject) throw IllegalStateException("synthetic foreground start rejection")
      return ComponentName(this, AlarmPlaybackService::class.java)
    }
  }

  private fun start(context: StartContext, action: String): PowerManager.WakeLock {
    AlarmServiceStarter.start(context, Intent(context, AlarmPlaybackService::class.java).setAction(action))
    return ShadowPowerManager.getLatestWakeLock()
  }

  private fun expiryLooper(): Looper {
    val field = AlarmServiceStarter::class.java.getDeclaredField("handler")
      .apply { isAccessible = true }
    return (field.get(AlarmServiceStarter) as Handler).looper
  }

  private fun scheduledBroadcast(context: StartContext, atMs: Long, action: String): Intent {
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    AlarmPlaybackService.clearPending()
    val alarm = NativeAlarm(
      "wake", "Wake", 7, 10, emptyList(), true,
      NativeAlarmSource.Station("jazz"), 0.8f, 20, 10, 30, 1,
    )
    val occurrence = ScheduledOccurrence(
      alarm.id, AlarmSchedule.occurrenceId(alarm.id, atMs, false), atMs, false,
    )
    AlarmStateStore.update(context) {
      PersistedAlarmState(
        initialized = true, alarms = listOf(alarm),
        stations = listOf(NativeStation("jazz", "Jazz", "https://radio.example/live")),
        scheduled = mapOf(alarm.id to occurrence),
      )
    }
    AndroidAlarmScheduler.replaceRegular(context, alarm.id)
    val delivery = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
      .scheduledAlarms.single { Shadows.shadowOf(it.operation).savedIntent.action == action }
    return Shadows.shadowOf(delivery.operation).savedIntent
  }

  @Test fun preparationReceiverPassesTheWakeHoldToItsServiceRequest() {
    val context = StartContext()
    val delivery = scheduledBroadcast(
      context, System.currentTimeMillis() + 30_000L, AndroidAlarmScheduler.ACTION_PREPARE,
    )
    AlarmReceiver().onReceive(context, delivery)
    val wakeLock = ShadowPowerManager.getLatestWakeLock()
    assertTrue(wakeLock.isHeld)
    assertEquals(AlarmPlaybackService.ACTION_PREPARE, context.starts.single().action)
    assertTrue(AlarmStateStore.snapshot(context).alarms.single().enabled)
    AlarmServiceStarter.complete(context.starts.single())
    assertFalse(wakeLock.isHeld)
  }

  @Test fun fireReceiverPassesTheWakeHoldAfterClaimingItsOccurrence() {
    val context = StartContext()
    val delivery = scheduledBroadcast(
      context, System.currentTimeMillis() - 1_000L, AndroidAlarmScheduler.ACTION_FIRE,
    )
    try {
      AlarmReceiver().onReceive(context, delivery)
      val wakeLock = ShadowPowerManager.getLatestWakeLock()
      assertTrue(wakeLock.isHeld)
      assertEquals(AlarmPlaybackService.ACTION_RING, context.starts.single().action)
      assertFalse(AlarmStateStore.snapshot(context).alarms.single().enabled)
      AlarmServiceStarter.complete(context.starts.single())
      assertFalse(wakeLock.isHeld)
    } finally {
      AlarmPlaybackService.clearPending()
    }
  }

  @Test fun cpuHoldIsAcquiredBeforeDispatchAndReleasedAfterServiceSetup() {
    val context = StartContext()
    val wakeLock = start(context, AlarmPlaybackService.ACTION_PREPARE)
    assertTrue(wakeLock.isHeld)
    assertEquals(AlarmPlaybackService.ACTION_PREPARE, context.starts.single().action)

    AlarmServiceStarter.complete(context.starts.single())
    assertFalse(wakeLock.isHeld)
    AlarmServiceStarter.complete(context.starts.single())
    AlarmServiceStarter.complete(null)
    AlarmServiceStarter.complete(Intent())
  }

  @Test fun completingPreparationDoesNotReleaseAnOverlappingRingStart() {
    val context = StartContext()
    val prepareLock = start(context, AlarmPlaybackService.ACTION_PREPARE)
    val ringLock = start(context, AlarmPlaybackService.ACTION_RING)

    AlarmServiceStarter.complete(context.starts[0])
    assertFalse(prepareLock.isHeld)
    assertTrue(ringLock.isHeld)
    AlarmServiceStarter.complete(context.starts[0])
    assertTrue(ringLock.isHeld)
    AlarmServiceStarter.complete(context.starts[1])
    assertFalse(ringLock.isHeld)
  }

  @Test fun foregroundStartRejectionReleasesOnlyTheRejectedHandoff() {
    val context = StartContext()
    val pendingLock = start(context, AlarmPlaybackService.ACTION_PREPARE)
    context.reject = true
    try {
      start(context, AlarmPlaybackService.ACTION_RING)
      throw AssertionError("Expected start rejection")
    } catch (_: IllegalStateException) {
      assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
      assertTrue(pendingLock.isHeld)
    } finally {
      AlarmServiceStarter.complete(context.starts[0])
    }
  }

  @Test fun undeliveredStartExpiresWithoutReleasingANewerStart() {
    val context = StartContext()
    val missingLock = start(context, AlarmPlaybackService.ACTION_PREPARE)
    val expiry = Shadows.shadowOf(expiryLooper())
    expiry.pause()
    expiry.idleFor(Duration.ofSeconds(10))
    val newerLock = start(context, AlarmPlaybackService.ACTION_RING)
    expiry.idleFor(
      Duration.ofMillis(AlarmServiceStarter.WAKE_TIMEOUT_MS - 10_000L),
    )

    assertFalse(missingLock.isHeld)
    assertTrue(newerLock.isHeld)
    AlarmServiceStarter.complete(context.starts[0])
    assertTrue(newerLock.isHeld)
    AlarmServiceStarter.complete(context.starts[1])
    assertFalse(newerLock.isHeld)
  }

  @Test fun timeoutExpiresEvenWhileTheMainLooperHasNotRun() {
    val context = StartContext()
    val wakeLock = start(context, AlarmPlaybackService.ACTION_PREPARE)
    val expiry = Shadows.shadowOf(expiryLooper())
    expiry.pause()
    var mainThreadRan = false
    Handler(Looper.getMainLooper()).post { mainThreadRan = true }

    expiry.idleFor(Duration.ofMillis(AlarmServiceStarter.WAKE_TIMEOUT_MS))

    assertFalse(wakeLock.isHeld)
    assertFalse(mainThreadRan)
    AlarmServiceStarter.complete(context.starts.single())
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(mainThreadRan)
  }

  @Test fun completedRingSetupCancelsItsDurableRecoveryGuard() {
    val context = StartContext()
    val delivery = scheduledBroadcast(context, System.currentTimeMillis() - 1_000L,
      AndroidAlarmScheduler.ACTION_FIRE)
    try {
      AlarmReceiver().onReceive(context, delivery)
      val clocks = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
      assertEquals(1, clocks.scheduledAlarms.count {
        Shadows.shadowOf(it.operation).savedIntent.action == AlarmServiceStarter.ACTION_RECOVER_START
      })
      val claimed = AlarmStateStore.snapshot(context).ringing
      AlarmServiceStarter.complete(context.starts.single())
      assertTrue(clocks.scheduledAlarms.none {
        Shadows.shadowOf(it.operation).savedIntent.action == AlarmServiceStarter.ACTION_RECOVER_START
      })
      assertEquals(claimed, AlarmStateStore.snapshot(context).ringing)
    } finally {
      context.starts.forEach(AlarmServiceStarter::complete)
      AlarmPlaybackService.clearPending()
    }
  }

  @Test fun osGuardRecoversAClaimWhenTheStartingProcessNoLongerOwnsIt() {
    val context = StartContext()
    val delivery = scheduledBroadcast(context, System.currentTimeMillis() - 1_000L,
      AndroidAlarmScheduler.ACTION_FIRE)
    try {
      AlarmReceiver().onReceive(context, delivery)
      val originalRing = AlarmStateStore.snapshot(context).ringing!!
      val guard = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        .scheduledAlarms.single {
          Shadows.shadowOf(it.operation).savedIntent.action == AlarmServiceStarter.ACTION_RECOVER_START
        }.let { Shadows.shadowOf(it.operation).savedIntent }
      AlarmPlaybackService.clearPending() // No in-process service owns the durable claim.
      AlarmReceiver().onReceive(context, guard)
      val recovered = AlarmStateStore.snapshot(context)
      assertNull(recovered.ringing)
      assertNull(AlarmPlaybackService.activeOccurrenceId())
      val retry = recovered.snoozes.getValue(originalRing.alarm.id)
      assertEquals(originalRing.deliveryExpiresElapsedMs, retry.expiresElapsedMs)
      assertTrue(kotlin.math.abs(retry.expiresAtMs!! - originalRing.deliveryExpiresAtMs!!) < 1_000L)
      assertTrue(retry.atMs > System.currentTimeMillis())
      assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
        it.alarmClockInfo?.triggerTime == retry.atMs
      })
    } finally {
      context.starts.forEach(AlarmServiceStarter::complete)
      AlarmPlaybackService.clearPending()
    }
  }

  @Test fun missingRingStartTimesOutAndClearsAnAbandonedTestClaim() {
    val context = StartContext()
    val delivery = scheduledBroadcast(context, System.currentTimeMillis() - 1_000L,
      AndroidAlarmScheduler.ACTION_FIRE)
    try {
      AlarmReceiver().onReceive(context, delivery)
      val before = AlarmStateStore.snapshot(context)
      AlarmStateStore.update(context) { before.copy(ringing = before.ringing!!.copy(trigger = "test")) }
      val wakeLock = ShadowPowerManager.getLatestWakeLock()
      Shadows.shadowOf(expiryLooper()).idleFor(Duration.ofMillis(AlarmServiceStarter.WAKE_TIMEOUT_MS))
      assertFalse(wakeLock.isHeld)
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
      assertNull(AlarmPlaybackService.activeOccurrenceId())
    } finally {
      context.starts.forEach(AlarmServiceStarter::complete)
      AlarmPlaybackService.clearPending()
    }
  }

  @Test fun staleRecoveryGuardCannotClearANewerPendingRing() {
    val context = StartContext()
    val delivery = scheduledBroadcast(context, System.currentTimeMillis() - 1_000L,
      AndroidAlarmScheduler.ACTION_FIRE)
    try {
      AlarmReceiver().onReceive(context, delivery)
      val guard = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        .scheduledAlarms.single {
          Shadows.shadowOf(it.operation).savedIntent.action == AlarmServiceStarter.ACTION_RECOVER_START
        }.let { Shadows.shadowOf(it.operation).savedIntent }
      val newer = AlarmStateStore.snapshot(context).ringing!!.copy(occurrenceId = "newer-ring")
      AlarmPlaybackService.markPending(newer.occurrenceId)
      AlarmStateStore.update(context) { it.copy(ringing = newer) }
      AlarmReceiver().onReceive(context, guard)
      assertEquals(newer, AlarmStateStore.snapshot(context).ringing)
      assertEquals(newer.occurrenceId, AlarmPlaybackService.activeOccurrenceId())
      assertNotNull(AlarmStateStore.snapshot(context).ringing)
    } finally {
      context.starts.forEach(AlarmServiceStarter::complete)
      AlarmPlaybackService.clearPending()
    }
  }
}
