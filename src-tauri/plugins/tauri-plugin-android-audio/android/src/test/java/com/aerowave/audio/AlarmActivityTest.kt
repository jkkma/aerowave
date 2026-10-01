package com.aerowave.audio

import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmActivityTest {
  @Test fun fullScreenLaunchesAreTiedToOneOccurrence() {
    val context = RuntimeEnvironment.getApplication()
    val first = AlarmActivity.intentFor(context, "morning:1")
    val second = AlarmActivity.intentFor(context, "morning:2")
    assertFalse(first.filterEquals(second))
    assertEquals("morning:1", first.getStringExtra("occurrenceId"))
    assertEquals("morning:2", second.getStringExtra("occurrenceId"))
  }

  @Test fun staleLaunchFinishesWithoutHoldingTheDisplayOn() {
    val context = RuntimeEnvironment.getApplication()
    val activity = Robolectric.buildActivity(
      AlarmActivity::class.java, AlarmActivity.intentFor(context, "already-dismissed"),
    ).create().start().resume().get()
    assertTrue(activity.isFinishing)
    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  @Test fun recreatedOldAlarmTaskBindsToTheNewLiveClaimBeforeItsNewIntentArrives() {
    val context = RuntimeEnvironment.getApplication()
    val current = ringingRecord("wake:new")
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = current) }
    val serviceController = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    serviceController.get().onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, current.occurrenceId),
      0, 1,
    )
    val activityController = Robolectric.buildActivity(
      AlarmActivity::class.java, AlarmActivity.intentFor(context, "wake:old"),
    ).create().start().resume()
    val activity = activityController.get()
    assertFalse(activity.isFinishing)
    assertEquals(current.occurrenceId, activity.intent.getStringExtra("occurrenceId"))
    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

    activityController.newIntent(AlarmActivity.intentFor(context, current.occurrenceId))
    assertFalse(activity.isFinishing)
    activityController.pause().stop().destroy()
    serviceController.destroy()
  }

  @Test fun oldAlarmTaskCannotRecoverWithoutMatchingLiveService() {
    val context = RuntimeEnvironment.getApplication()
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = ringingRecord("wake:new")) }
    val activity = Robolectric.buildActivity(
      AlarmActivity::class.java, AlarmActivity.intentFor(context, "wake:old"),
    ).create().start().resume().get()
    assertTrue(activity.isFinishing)
    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun ringingRecord(occurrenceId: String, trigger: String = "scheduled") = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Folder("content://missing"),
      volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = occurrenceId, trigger = trigger,
    startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000,
    sourceUri = "content://missing/audio",
  )

  private fun withRingingActivity(
    ring: RingingRecord,
    check: (AlarmActivity, AlarmPlaybackService, ActivityController<AlarmActivity>) -> Unit,
  ) {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = ring) }
    val serviceController = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = serviceController.get()
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, ring.occurrenceId),
      0, 1,
    )
    val activityController = Robolectric.buildActivity(
      AlarmActivity::class.java, AlarmActivity.intentFor(context, ring.occurrenceId),
    ).create().start().resume()
    try {
      check(activityController.get(), service, activityController)
    } finally {
      activityController.pause().stop().destroy()
      serviceController.destroy()
    }
  }

  @Test fun eitherVolumeButtonSnoozesOnceOnReleaseDespiteKeyRepeats() {
    val context = RuntimeEnvironment.getApplication()
    for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
      val ring = ringingRecord("wake:volume:$key")
      withRingingActivity(ring) { activity, _, _ ->
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
        repeat(3) { count ->
          assertTrue(activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, key, count + 1)))
        }
        assertEquals(ring.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val state = AlarmStateStore.snapshot(context)
        assertNull(state.ringing)
        assertEquals(1, state.snoozes.size)
        val snooze = state.snoozes.getValue(ring.alarm.id)
        assertTrue(snooze.snoozed)
        assertTrue(snooze.occurrenceId != ring.occurrenceId)
        assertTrue(snooze.atMs > System.currentTimeMillis() + 9 * 60_000L)
        assertTrue(activity.isFinishing)
      }
    }
  }

  @Test fun canceledOrUnpairedVolumeReleaseCannotSnooze() {
    val context = RuntimeEnvironment.getApplication()
    val ring = ringingRecord("wake:cancel")
    withRingingActivity(ring) { activity, _, _ ->
      val key = KeyEvent.KEYCODE_VOLUME_DOWN
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
      activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, key, 1))
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
      activity.dispatchKeyEvent(KeyEvent.changeFlags(
        KeyEvent(KeyEvent.ACTION_UP, key), KeyEvent.FLAG_CANCELED,
      ))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertEquals(ring.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertEquals(1, AlarmStateStore.snapshot(context).snoozes.size)
    }
  }

  @Test fun volumeReleaseFromAnOldRingCannotSnoozeItsReplacement() {
    val context = RuntimeEnvironment.getApplication()
    val old = ringingRecord("wake:old-press")
    withRingingActivity(old) { activity, service, activityController ->
      val key = KeyEvent.KEYCODE_VOLUME_UP
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
      val newer = ringingRecord("wake:new-press")
      AlarmStateStore.update(context) { it.copy(ringing = newer) }
      service.onStartCommand(
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, newer.occurrenceId),
        0, 2,
      )
      activityController.newIntent(AlarmActivity.intentFor(context, newer.occurrenceId))
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertEquals(newer.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertEquals(1, AlarmStateStore.snapshot(context).snoozes.size)
    }
  }

  @Test fun volumeButtonStopsATestRingWithoutSchedulingASnooze() {
    val context = RuntimeEnvironment.getApplication()
    withRingingActivity(ringingRecord("test:volume", "test")) { activity, _, _ ->
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
      activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
    }
  }

  @Test fun mainScreenVolumeActionRejectsOldOccurrencesAndStopsTestRings() {
    val context = RuntimeEnvironment.getApplication()
    val ring = ringingRecord("wake:main")
    withRingingActivity(ring) { _, _, _ ->
      AlarmPlaybackService.volumeButtonIfMatching(context, "wake:old")
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertEquals(ring.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      AlarmPlaybackService.volumeButtonIfMatching(context, ring.occurrenceId)
      AlarmPlaybackService.volumeButtonIfMatching(context, ring.occurrenceId)
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertEquals(1, AlarmStateStore.snapshot(context).snoozes.size)
    }
    withRingingActivity(ringingRecord("test:main", "test")) { _, _, _ ->
      AlarmPlaybackService.volumeButtonIfMatching(context, "test:main")
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
    }
  }

  @Test fun visibleAlarmClosesAndReleasesScreenHoldAfterDismissal() {
    val context = RuntimeEnvironment.getApplication()
    val ring = RingingRecord(
      alarm = NativeAlarm(
        id = "wake", label = "Wake", hour = 7, minute = 0,
        days = emptyList(), enabled = true,
        source = NativeAlarmSource.Folder("content://missing"),
        volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
        autoStopMins = 0, autoSnoozes = 0,
      ),
      occurrenceId = "wake:now", trigger = "scheduled",
      startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000,
      sourceUri = "content://missing/audio",
    )
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = ring) }
    val serviceController = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    serviceController.get().onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, ring.occurrenceId),
      0, 1,
    )
    val activityController = Robolectric.buildActivity(
      AlarmActivity::class.java, AlarmActivity.intentFor(context, ring.occurrenceId),
    ).create().start().resume()
    val activity = activityController.get()
    assertFalse(activity.isFinishing)
    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

    AlarmPlaybackService.dismissIfMatching(context, ring.occurrenceId)
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
    assertTrue(activity.isFinishing)
    assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    activityController.pause().stop().destroy()
    serviceController.destroy()
  }
}
