package com.aerowave.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmInputTest {
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

  @Test fun deliberateAlternatingShakesTriggerOnce() {
    val detector = AlarmShakeDetector()
    var time = 1_000L
    for (index in 0..3) {
      val direction = if (index % 2 == 0) 16f else -16f
      assertFalse(detector.sample(0f, 0f, 0f, time))
      val triggered = detector.sample(direction, 0f, 0f, time + 30)
      assertTrue(triggered == (index == 3))
      assertFalse(detector.sample(direction, 0f, 0f, time + 40))
      time += 280
    }
  }

  @Test fun normalMovementSingleJoltAndOneDirectionDoNotSnooze() {
    val detector = AlarmShakeDetector()
    for (index in 0..40) {
      assertFalse(detector.sample(4f, 3f, 0f, index * 50L))
    }
    assertFalse(detector.sample(18f, 0f, 0f, 2_100))
    assertFalse(detector.sample(0f, 0f, 0f, 2_140))
    for (index in 1..6) {
      assertFalse(detector.sample(18f, 0f, 0f, 2_100L + index * 260))
      assertFalse(detector.sample(0f, 0f, 0f, 2_140L + index * 260))
    }
  }

  @Test fun slowShakesCannotAccumulateAcrossMinutes() {
    val detector = AlarmShakeDetector()
    for (index in 0..7) {
      val time = index * 1_000L
      assertFalse(detector.sample(0f, 0f, 0f, time))
      assertFalse(detector.sample(if (index % 2 == 0) 17f else -17f, 0f, 0f, time + 30))
    }
  }
}
