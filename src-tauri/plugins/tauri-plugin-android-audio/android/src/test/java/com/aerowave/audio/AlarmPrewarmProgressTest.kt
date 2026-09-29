package com.aerowave.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPrewarmProgressTest {
  private var elapsedMs = 1_000L
  private val progress = AlarmPrewarmProgress { elapsedMs }

  @Test
  fun slowBufferingDoesNotCountAsReadyOrResetTheAttempt() {
    progress.reset()
    elapsedMs += 55_000
    progress.sample(0, false)
    assertFalse(progress.isReady(false))

    elapsedMs += 1_000
    progress.sample(400, true)
    assertTrue(progress.isReady(true))
  }

  @Test
  fun dueTimeHandoffNeedsRecentAdvanceAndActivePlayback() {
    progress.reset(30_000)
    progress.sample(30_000, true)
    assertFalse(progress.isReady(true))

    elapsedMs += 1_000
    progress.sample(31_000, true)
    assertTrue(progress.isReady(true))
    assertFalse(progress.isReady(false))

    elapsedMs += 3_001
    assertFalse(progress.isReady(true))
    progress.sample(32_000, true)
    assertTrue(progress.isReady(true))
  }
}
