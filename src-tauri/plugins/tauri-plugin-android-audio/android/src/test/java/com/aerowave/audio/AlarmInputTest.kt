package com.aerowave.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmInputTest {
  @Test fun volumeButtonsSnoozeRealRingsAndStopTests() {
    assertTrue(alarmVolumeButtonSnoozes("scheduled"))
    assertTrue(alarmVolumeButtonSnoozes("snooze"))
    assertFalse(alarmVolumeButtonSnoozes("test"))
  }

  @Test fun eitherVolumeKeyActsOnceAfterACompletedPress() {
    for (key in listOf(24, 25)) {
      val gate = AlarmInputGate()
      gate.bind("ring")
      assertFalse(gate.volumeUp(key, "ring"))
      assertTrue(gate.volumeDown(key, 1, "ring"))
      assertFalse(gate.volumeUp(key, "ring"))
      assertTrue(gate.volumeDown(key, 0, "ring"))
      repeat(3) { assertTrue(gate.volumeDown(key, it + 1, "ring")) }
      assertTrue(gate.volumeUp(key, "ring"))
      assertFalse(gate.volumeUp(key, "ring"))
      assertTrue(gate.beginAction("ring"))
      assertFalse(gate.volumeDown(key, 0, "ring"))
      assertFalse(gate.beginAction("ring"))
    }
  }

  @Test fun volumeReleaseNeedsFreshPressOnTheSameOccurrence() {
    val gate = AlarmInputGate()
    gate.bind("first")
    assertTrue(gate.volumeDown(24, 0, "first"))
    gate.bind("second")
    assertFalse(gate.volumeUp(24, "second"))
    assertTrue(gate.volumeDown(24, 1, "second"))
    assertFalse(gate.volumeUp(24, "second"))
    assertTrue(gate.volumeDown(24, 0, "second"))
    assertFalse(gate.volumeUp(25, "second"))
    assertTrue(gate.volumeDown(24, 0, "second"))
    assertTrue(gate.volumeUp(24, "second"))
    assertTrue(gate.beginAction("second"))
    assertFalse(gate.beginAction("second"))
  }

  @Test fun failedActionCanRetryOnlyItsOwnOccurrence() {
    val gate = AlarmInputGate()
    gate.bind("first")
    assertTrue(gate.beginAction("first"))
    gate.bind("second")
    gate.actionFailed("first")
    assertTrue(gate.beginAction("second"))
    assertFalse(gate.beginAction("first"))
  }

  @Test fun lineageCalibrationUsesSixSamplesAndChecksOnTheSeventh() {
    val detector = AlarmShakeDetector()
    repeat(6) { assertFalse(detector.sample(13.3f, 0f, 0f)) }
    assertFalse(detector.sample(0f, 0f, 0f))
    repeat(6) { assertFalse(detector.sample(13.4f, 0f, 0f)) }
    assertTrue(detector.sample(0f, 0f, 0f))
    assertFalse(detector.sample(0f, 0f, 0f))
  }

  @Test fun mixedAxisContinuousBurstTriggersWithoutQuietValleys() {
    // Synthetic gravity-free motion that rotates across axes without settling.
    val motion = arrayOf(
      floatArrayOf(2f, 1f, 0f),
      floatArrayOf(3f, -2f, 1f),
      floatArrayOf(4f, -1f, 0f),
      floatArrayOf(-2f, 3f, -1f),
      floatArrayOf(1f, 2f, 2f),
      floatArrayOf(0f, -3f, 1f),
      floatArrayOf(22f, 2f, 0f),
      floatArrayOf(20f, 10f, -3f),
      floatArrayOf(3f, 25f, -2f),
      floatArrayOf(-10f, 25f, 5f),
      floatArrayOf(-25f, 8f, 4f),
      floatArrayOf(-20f, -10f, 1f),
      floatArrayOf(-2f, -26f, 0f),
      floatArrayOf(8f, -20f, 1f),
      floatArrayOf(18f, -10f, 2f),
    )
    val detector = AlarmShakeDetector()
    val triggerIndexes = motion.indices.filter { index ->
      val vector = motion[index]
      detector.sample(vector[0], vector[1], vector[2])
    }
    assertEquals(listOf(13), triggerIndexes)
  }

  @Test fun strongMotionOnAnyAxisDoesNotNeedGeometricReversal() {
    val detector = AlarmShakeDetector()
    repeat(6) { assertFalse(detector.sample(20f, 0f, 0f)) }
    assertTrue(detector.sample(0f, 0f, 0f))
    repeat(6) { assertFalse(detector.sample(0f, -20f, 0f)) }
    assertTrue(detector.sample(0f, 0f, 0f))
  }

  @Test fun ordinaryMotionAndOneJoltDoNotDismiss() {
    val detector = AlarmShakeDetector()
    repeat(28) { index ->
      val x = if (index == 14) 40f else 4f
      assertFalse(detector.sample(x, 2f, 0f))
    }
  }

  @Test fun resetDropsAnIncompleteWindowBetweenRings() {
    val detector = AlarmShakeDetector()
    repeat(5) { assertFalse(detector.sample(20f, 0f, 0f)) }
    detector.reset()
    assertFalse(detector.sample(20f, 0f, 0f))
    repeat(5) { assertFalse(detector.sample(0f, 0f, 0f)) }
    assertFalse(detector.sample(0f, 0f, 0f))
  }
}
