package com.aerowave.audio

import android.os.Looper
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
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

  private fun ringingRecord(occurrenceId: String) = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Folder("content://missing"),
      volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = occurrenceId, trigger = "scheduled",
    startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000,
    sourceUri = "content://missing/audio",
  )

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
