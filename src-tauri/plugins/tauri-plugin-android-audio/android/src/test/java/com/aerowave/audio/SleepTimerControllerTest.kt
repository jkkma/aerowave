package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerControllerTest {
  private var elapsedMs = 1_000L
  private var wallMs = 1_700_000_000_000L

  private fun controller(initial: SleepTimerSnapshot = SleepTimerSnapshot()) =
    SleepTimerController(initial, { elapsedMs }, { wallMs })

  @Test
  fun deadlineAndRemainingUseElapsedRealtime() {
    val timer = controller()
    val started = timer.start(1)
    assertEquals(wallMs + 60_000, started.timer?.endsAtMs)
    assertEquals(60_000L, started.timer?.remainingMs)

    wallMs += 3_600_000
    elapsedMs += 12_345
    assertEquals(47_655L, timer.snapshot().timer?.remainingMs)
  }

  @Test
  fun fadeIsLinearOnlyDuringLastTwentySeconds() {
    val timer = controller()
    timer.start(1)
    assertEquals(1f, timer.fadeMultiplier(), 0.0001f)
    elapsedMs += 50_000
    assertEquals(0.5f, timer.fadeMultiplier(), 0.0001f)
    elapsedMs += 10_000
    assertTrue(timer.finishIfDue())
    assertEquals(1f, timer.fadeMultiplier(), 0.0001f)
  }

  @Test
  fun finishCreatesFenceThatReplacementAndCancelCannotErase() {
    val timer = controller()
    val capturedRevision = timer.start(1).revision
    elapsedMs += 60_000
    assertTrue(timer.finishIfDue())
    val finished = timer.snapshot()
    assertEquals(SLEEP_OUTCOME_FINISHED, finished.outcome)
    assertTrue(timer.shouldRejectPlay(capturedRevision))
    assertFalse(timer.shouldRejectPlay(finished.revision))

    timer.start(5)
    timer.cancel()
    assertTrue(timer.shouldRejectPlay(capturedRevision))
    assertFalse(timer.shouldRejectPlay(timer.snapshot().revision))
  }

  @Test
  fun replacementGetsNewDeadlineAndRevision() {
    val timer = controller()
    val first = timer.start(5)
    elapsedMs += 5_000
    wallMs += 5_000
    val replacement = timer.start(10)
    assertEquals(first.revision + 1, replacement.revision)
    assertEquals(10, replacement.timer?.minutes)
    assertEquals(600_000L, replacement.timer?.remainingMs)
    assertNull(replacement.outcome)
  }

  @Test
  fun cancellingWithoutTimerDoesNotInventRevisionOrOutcome() {
    val timer = controller()
    val unchanged = timer.cancel()
    assertEquals(0, unchanged.revision)
    assertNull(unchanged.outcome)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsDurationsOutsideOneDay() {
    controller().start(1_441)
  }
}
