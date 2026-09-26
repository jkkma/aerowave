package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmPlaybackProgressTest {
  private var elapsedMs = 1_000L
  private val progress = AlarmPlaybackProgress { elapsedMs }

  @Test
  fun focusPauseDoesNotConsumeStationStartupDeadline() {
    progress.start(pausedForFocus = false)
    elapsedMs += 3_000
    progress.pauseForFocus()
    elapsedMs += 30_000
    assertNull(progress.failureReason(0, false, false, true))

    progress.resumeFromFocus(0)
    elapsedMs += 11_000
    assertNull(progress.failureReason(0, false, true, true))
    elapsedMs += 1_000
    assertEquals(
      "The alarm source connected but did not produce audio",
      progress.failureReason(0, false, true, true),
    )
  }

  @Test
  fun focusGainAllowsFullStallGraceWithoutInventingProgress() {
    progress.start(pausedForFocus = false)
    elapsedMs += 1_000
    assertNull(progress.failureReason(1_000, true, true, true))
    progress.pauseForFocus()
    elapsedMs += 30_000
    assertNull(progress.failureReason(1_000, false, false, true))

    progress.resumeFromFocus(1_000)
    elapsedMs += 11_000
    assertNull(progress.failureReason(1_000, false, true, true))
    elapsedMs += 1_000
    assertEquals(
      "The alarm source stopped producing audio",
      progress.failureReason(1_000, false, true, true),
    )
  }

  @Test
  fun progressAfterFocusGainRetainsHealthySource() {
    progress.start(pausedForFocus = true)
    elapsedMs += 60_000
    assertNull(progress.failureReason(0, false, false, true))
    progress.resumeFromFocus(0)
    elapsedMs += 5_000
    assertNull(progress.failureReason(5_000, true, true, true))
    elapsedMs += 10_000
    assertNull(progress.failureReason(15_000, true, true, true))
  }
}
