package com.aerowave.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SustainedMediaProgressTest {
  private var elapsedMs = 1_000L
  private val progress = SustainedMediaProgress { elapsedMs }

  @Test
  fun restoresBudgetOnlyAfterFifteenSecondsOfAdvancingPlayhead() {
    assertFalse(progress.sample(true, 0))
    for (second in 1..14) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, second * 1_000L))
    }
    elapsedMs += 1_000
    assertTrue(progress.sample(true, 15_000))
  }

  @Test
  fun aReadyButStalledPlayerCannotRestoreBudget() {
    assertFalse(progress.sample(true, 0))
    repeat(30) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, 0))
    }
  }

  @Test
  fun aStallBetweenPlayingSegmentsBreaksSustainedProgress() {
    assertFalse(progress.sample(true, 0))
    repeat(10) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, (it + 1) * 1_000L))
    }
    repeat(30) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, 10_000))
    }
    repeat(14) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, 11_000L + it * 1_000L))
    }
    elapsedMs += 1_000
    assertTrue(progress.sample(true, 25_000))
  }

  @Test
  fun pauseAndTimelineJumpBreakSustainedProgress() {
    assertFalse(progress.sample(true, 0))
    repeat(10) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, (it + 1) * 1_000L))
    }
    assertFalse(progress.sample(false, 10_000))
    elapsedMs += 1_000
    assertFalse(progress.sample(true, 10_000))
    repeat(10) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, 11_000L + it * 1_000L))
    }
    elapsedMs += 1_000
    assertFalse(progress.sample(true, 50_000))
    repeat(14) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, 51_000L + it * 1_000L))
    }
    elapsedMs += 1_000
    assertTrue(progress.sample(true, 65_000))
  }

  @Test
  fun elapsedTimeWithoutEquivalentMediaProgressCannotRestoreBudget() {
    assertFalse(progress.sample(true, 0))
    repeat(14) {
      elapsedMs += 1_000
      assertFalse(progress.sample(true, (it + 1) * 1_000L))
    }
    elapsedMs += 60_000
    assertFalse(progress.sample(true, 14_500))
    elapsedMs += 1_000
    assertFalse(progress.sample(true, -1))
  }
}
