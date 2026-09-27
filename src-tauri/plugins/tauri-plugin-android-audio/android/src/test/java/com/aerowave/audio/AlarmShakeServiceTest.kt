package com.aerowave.audio

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmShakeServiceTest {
  private fun sendAcceleration(
    sensors: ShadowSensorManager, sensor: Sensor, x: Float, elapsedMs: Long,
  ) {
    val event = ShadowSensorManager.createSensorEvent(3)
    event.sensor = sensor
    event.timestamp = elapsedMs * 1_000_000L
    event.values[0] = x
    event.values[2] = 9.81f
    sensors.sendSensorEventToListeners(event, sensor)
  }

  private fun ring(trigger: String) = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Folder("content://missing"),
      volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = "$trigger:occurrence", trigger = trigger,
    startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000L,
    sourceUri = "content://missing/audio",
  )

  @Test fun realRingRegistersSensorAndDestroyUnregistersIt() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring("scheduled")
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val shadowSensors = Shadows.shadowOf(sensorManager)
    shadowSensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId),
      0, 1,
    )
    assertTrue(shadowSensors.hasListener(service))
    assertTrue(AlarmPlaybackService.canShakeToSnooze(active.occurrenceId))

    AlarmPlaybackService.dismissIfMatching(context, active.occurrenceId)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertFalse(shadowSensors.hasListener(service))
    assertFalse(AlarmPlaybackService.canShakeToSnooze(active.occurrenceId))

    controller.destroy()
    assertFalse(shadowSensors.hasListener(service))
    assertFalse(AlarmPlaybackService.canShakeToSnooze(active.occurrenceId))
  }

  @Test fun testRingAndMissingSensorDoNotRegisterShake() {
    val context = RuntimeEnvironment.getApplication()
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val shadowSensors = Shadows.shadowOf(sensorManager)
    val sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
    shadowSensors.addSensor(sensor)
    val testRing = ring("test")
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = testRing) }
    val testController = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val testService = testController.get()
    testService.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, testRing.occurrenceId),
      0, 1,
    )
    assertFalse(shadowSensors.hasListener(testService))
    assertFalse(AlarmPlaybackService.canShakeToSnooze(testRing.occurrenceId))
    for (index in 0..3) {
      val at = 1_000L + index * 280
      sendAcceleration(shadowSensors, sensor, 0f, at)
      sendAcceleration(shadowSensors, sensor, if (index % 2 == 0) 35f else -35f, at + 30)
    }
    assertEquals(testRing.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
    assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
    testController.destroy()

    shadowSensors.setForceListenersToFail(true)
    val realRing = ring("scheduled")
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = realRing) }
    val realController = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val realService = realController.get()
    realService.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, realRing.occurrenceId),
      0, 2,
    )
    assertFalse(shadowSensors.hasListener(realService))
    assertFalse(AlarmPlaybackService.canShakeToSnooze(realRing.occurrenceId))
    realController.destroy()
  }

  @Test fun repeatedShakeSchedulesOneDurableSnoozeAndStopsListening() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring("scheduled")
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val shadowSensors = Shadows.shadowOf(sensorManager)
    val sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
    shadowSensors.addSensor(sensor)
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId),
      0, 1,
    )
    assertTrue(shadowSensors.hasListener(service))

    for (index in 0..39) sendAcceleration(shadowSensors, sensor, 5f, index * 20L)
    sendAcceleration(shadowSensors, sensor, 0f, 800L)
    sendAcceleration(shadowSensors, sensor, 18f, 820L)
    sendAcceleration(shadowSensors, sensor, 0f, 840L)
    assertEquals(active.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
    assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())

    for (index in 0..5) {
      sendAcceleration(
        shadowSensors, sensor, if (index % 2 == 0) 35f else -35f, 860L + index * 20,
      )
      if (index < 5) {
        assertEquals(active.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      }
    }
    val saved = AlarmStateStore.snapshot(context)
    assertNull(saved.ringing)
    assertEquals(1, saved.snoozes.size)
    val snooze = saved.snoozes.getValue(active.alarm.id)
    assertTrue(snooze.snoozed)
    assertTrue(snooze.occurrenceId != active.occurrenceId)
    assertTrue(snooze.atMs > System.currentTimeMillis() + 9 * 60_000L)
    assertFalse(shadowSensors.hasListener(service))
    assertFalse(AlarmPlaybackService.canShakeToSnooze(active.occurrenceId))
    controller.destroy()
  }
}
