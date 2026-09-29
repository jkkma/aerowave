package com.aerowave.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmPreparationServiceTest {
  private fun ring() = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Folder("content://missing"),
      volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = "wake:claimed", trigger = "test",
    startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000L,
    sourceUri = "content://missing/audio",
  )

  @Test fun latePrepareKeepsLiveRingRestartable() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring()
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId),
      0, 1,
    )

    val result = service.onStartCommand(
      AlarmPlaybackService.prepareIntentFor(
        context, active.alarm.id, active.occurrenceId, active.startedAtMs, false,
      ),
      0, 2,
    )
    assertEquals(Service.START_STICKY, result)
    assertTrue(AlarmPlaybackService.isRinging())
    assertEquals(active.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
    controller.destroy()
  }

  @Test fun prepareAfterDurableClaimStartsRingAndNullRestartRecoversIt() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring()
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val first = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val result = first.get().onStartCommand(
      AlarmPlaybackService.prepareIntentFor(
        context, active.alarm.id, active.occurrenceId, active.startedAtMs, false,
      ),
      0, 1,
    )
    assertEquals(Service.START_STICKY, result)
    assertTrue(AlarmPlaybackService.isRinging())
    first.destroy()

    val restarted = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    assertEquals(Service.START_REDELIVER_INTENT, restarted.get().onStartCommand(null, 0, 2))
    assertTrue(AlarmPlaybackService.isRinging())
    restarted.destroy()
  }

  @Test fun adoptedStationIsDurableBeforeItsSingleInitialFullScreenNotification() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring()
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    val stationUri = "https://example.org/caprice.aac"

    service.postInitialRingingNotification(active, stationUri, "Radio Caprice", false)

    val persisted = AlarmStateStore.snapshot(context).ringing!!
    assertEquals("station", persisted.sourceKind)
    assertEquals(stationUri, persisted.sourceUri)
    assertEquals("Radio Caprice", persisted.title)
    val foreground = Shadows.shadowOf(service).lastForegroundNotification
    assertEquals(AlarmPlaybackService.NOTIFICATION_ID,
      Shadows.shadowOf(service).lastForegroundNotificationId)
    assertEquals("Radio Caprice", foreground.extras.getCharSequence(Notification.EXTRA_TEXT))
    assertSame(foreground, Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
      .getNotification(AlarmPlaybackService.NOTIFICATION_ID))
    controller.destroy()
  }
}
