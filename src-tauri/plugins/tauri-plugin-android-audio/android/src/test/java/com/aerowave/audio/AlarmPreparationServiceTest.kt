package com.aerowave.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmPreparationServiceTest {
  @Implements(Service::class)
  class QueuedStartServiceShadow : ShadowService() {
    var acceptedStartId = 0
    val checkedStartIds = mutableListOf<Int>()

    @Implementation
    override fun stopSelfResult(startId: Int): Boolean {
      checkedStartIds += startId
      return if (startId == acceptedStartId) super.stopSelfResult(startId) else false
    }
  }

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

  @Test fun coldServiceIsSilentAndForegroundBeforeAnyStartCommandArrives() {
    val context = RuntimeEnvironment.getApplication()
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    val foreground = Shadows.shadowOf(service).lastForegroundNotification
    val manager = context.getSystemService(NotificationManager::class.java)

    assertEquals(71_002, Shadows.shadowOf(service).lastForegroundNotificationId)
    assertSame(foreground, Shadows.shadowOf(manager).getNotification(71_002))
    assertEquals(NotificationManager.IMPORTANCE_LOW,
      manager.getNotificationChannel(foreground.channelId).importance)
    assertNull(foreground.fullScreenIntent)
    assertNull(foreground.sound)
    assertNull(foreground.vibrate)
    assertNull(Shadows.shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    assertNull(Shadows.shadowOf(context).nextStartedActivity)
    controller.destroy()
  }

  @Test fun malformedAndStalePreparationRemoveTheirBootstrapWithoutLeavingIdleService() {
    val context = RuntimeEnvironment.getApplication()
    AlarmStateStore.update(context) { PersistedAlarmState() }
    val intents = listOf(
      Intent(context, AlarmPlaybackService::class.java).setAction(AlarmPlaybackService.ACTION_PREPARE),
      AlarmPlaybackService.prepareIntentFor(context, "gone", "gone:old", System.currentTimeMillis() + 30_000, false),
    )
    for (intent in intents) {
      val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
      val service = controller.get()

      assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
      assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
      assertNull(Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        .getNotification(71_002))
      controller.destroy()
    }
  }

  @Test fun emptyRingAndDismissStartsRemoveTheirBootstrap() {
    val context = RuntimeEnvironment.getApplication()
    AlarmStateStore.update(context) { PersistedAlarmState() }
    for (action in listOf(AlarmPlaybackService.ACTION_RING, AlarmPlaybackService.ACTION_DISMISS)) {
      val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
      val service = controller.get()
      service.onStartCommand(AlarmPlaybackService.intentFor(context, action, "gone"), 0, 1)

      assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
      assertNull(Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        .getNotification(71_002))
      controller.destroy()
    }
  }

  @Test fun staleStartsDoNotStopOrReplaceAnActiveSilentPreparation() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val due = System.currentTimeMillis() + 30_000L
    val alarm = ring().alarm.copy(source = NativeAlarmSource.Station("radio"))
    val scheduled = ScheduledOccurrence(
      alarm.id, "wake:soon", due, false,
      heldUri = "content://missing/prepared", heldKind = "station",
    )
    AlarmStateStore.update(context) {
      PersistedAlarmState(
        alarms = listOf(alarm),
        stations = listOf(NativeStation("radio", "Radio", "https://example.org/radio")),
        scheduled = mapOf(alarm.id to scheduled),
      )
    }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.onStartCommand(
      AlarmPlaybackService.prepareIntentFor(context, alarm.id, scheduled.occurrenceId, due, false), 0, 1,
    )
    val preparation = Shadows.shadowOf(service).lastForegroundNotification

    service.onStartCommand(
      AlarmPlaybackService.prepareIntentFor(context, "gone", "gone:old", due, false), 0, 2,
    )
    service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, "gone"), 0, 3)

    assertSame(preparation, Shadows.shadowOf(service).lastForegroundNotification)
    assertEquals("Radio", preparation.extras.getCharSequence(Notification.EXTRA_TEXT))
    assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
    assertTrue(AlarmStateStore.snapshot(context).alarms.single().enabled)
    assertNull(AlarmStateStore.snapshot(context).ringing)
    assertNull(Shadows.shadowOf(context).nextStartedActivity)
    controller.destroy()
  }

  @Test
  @Config(shadows = [QueuedStartServiceShadow::class])
  fun preparationRevalidationKeepsAnAcceptedButUndeliveredFireAlive() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val due = System.currentTimeMillis() + 30_000L
    val alarm = ring().alarm.copy(source = NativeAlarmSource.Station("radio"))
    val scheduled = ScheduledOccurrence(
      alarm.id, "wake:soon", due, false,
      heldUri = "content://missing/prepared", heldKind = "station",
    )
    AlarmStateStore.update(context) {
      PersistedAlarmState(
        alarms = listOf(alarm),
        stations = listOf(NativeStation("radio", "Radio", "https://example.org/radio")),
        scheduled = mapOf(alarm.id to scheduled),
      )
    }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    val shadow = Shadow.extract<QueuedStartServiceShadow>(service)
    shadow.acceptedStartId = 1
    service.onStartCommand(
      AlarmPlaybackService.prepareIntentFor(context, alarm.id, scheduled.occurrenceId, due, false), 0, 1,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    val preparation = shadow.lastForegroundNotification

    val next = ring().copy(occurrenceId = "folder:claimed", alarm = ring().alarm.copy(id = "folder"))
    AlarmStateStore.update(context) { it.copy(ringing = next) }
    AlarmPlaybackService.markPending(next.occurrenceId)
    shadow.acceptedStartId = 2
    AlarmPlaybackService.revalidatePreparation(context)
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf(1), shadow.checkedStartIds)
    assertFalse(shadow.isStoppedBySelf)
    assertFalse(shadow.isForegroundStopped)
    assertSame(preparation, shadow.lastForegroundNotification)
    val field = AlarmPlaybackService::class.java.getDeclaredField("preparation")
    field.isAccessible = true
    assertNull(field.get(service))

    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, next.occurrenceId), 0, 2,
    )
    assertEquals(next.occurrenceId, AlarmPlaybackService.liveOccurrenceId(context))
    assertEquals(AlarmPlaybackService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
    controller.destroy()
  }

  @Test
  @Config(shadows = [QueuedStartServiceShadow::class])
  fun asynchronousDismissalKeepsAnAcceptedButUndeliveredNextStartAlive() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring()
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.systemToneFactory = { _, _ ->
      object : SystemAlarmTone {
        private var playing = false
        override fun play() { playing = true }
        override fun stop() { playing = false }
        override fun isPlaying() = playing
        override fun setVolume(volume: Float) {}
      }
    }
    val shadow = Shadow.extract<QueuedStartServiceShadow>(service)
    shadow.acceptedStartId = 7
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId), 0, 7,
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    val next = active.copy(occurrenceId = "next:claimed", alarm = active.alarm.copy(id = "next"))
    AlarmStateStore.update(context) { it.copy(ringing = next) }
    AlarmPlaybackService.markPending(next.occurrenceId)
    shadow.acceptedStartId = 8
    AlarmPlaybackService.stopIfMatching(context, active.occurrenceId, false)
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf(7), shadow.checkedStartIds)
    assertFalse(shadow.isStoppedBySelf)
    assertFalse(shadow.isForegroundStopped)
    assertEquals(next.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)

    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, next.occurrenceId), 0, 8,
    )
    assertEquals(next.occurrenceId, AlarmPlaybackService.liveOccurrenceId(context))
    assertEquals(AlarmPlaybackService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
    controller.destroy()
  }

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
    val notification = Shadows.shadowOf(service).lastForegroundNotification

    val result = service.onStartCommand(
      AlarmPlaybackService.prepareIntentFor(
        context, active.alarm.id, active.occurrenceId, active.startedAtMs, false,
      ),
      0, 2,
    )
    assertEquals(Service.START_STICKY, result)
    assertSame(notification, Shadows.shadowOf(service).lastForegroundNotification)
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
