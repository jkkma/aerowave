package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmFadeProgressTest {
  private var elapsedMs = 1_000L
  private val fade = AlarmFadeProgress { elapsedMs }

  @Test fun delayedStationStartupGetsTheFullFade() {
    fade.start(20)
    elapsedMs += 25_000
    fade.samplePlayer(0, isPlaying = false, focusPaused = false)
    assertEquals(0.02f, fade.multiplier(), 0.001f)

    fade.samplePlayer(300, isPlaying = true, focusPaused = false)
    assertEquals(0.02f, fade.multiplier(), 0.001f)
    elapsedMs += 10_000
    fade.samplePlayer(10_300, isPlaying = true, focusPaused = false)
    assertEquals(0.5f, fade.multiplier(), 0.001f)
    elapsedMs += 10_000
    fade.samplePlayer(20_300, isPlaying = true, focusPaused = false)
    assertEquals(1f, fade.multiplier(), 0.001f)
  }

  @Test fun stalledStationFallsBackToQuietTone() {
    fade.start(20)
    elapsedMs += 12_000
    fade.samplePlayer(0, isPlaying = false, focusPaused = false)
    fade.sourceChanged()
    elapsedMs += 3_000
    fade.sampleTone(isPlaying = true, focusPaused = false)
    assertEquals(0.02f, fade.multiplier(), 0.001f)
    elapsedMs += 5_000
    fade.sampleTone(isPlaying = true, focusPaused = false)
    assertEquals(0.25f, fade.multiplier(), 0.001f)
  }

  @Test fun bufferingAndFocusLossDoNotConsumeAudibleFadeTime() {
    fade.start(20)
    fade.samplePlayer(200, isPlaying = true, focusPaused = false)
    elapsedMs += 5_000
    fade.samplePlayer(5_200, isPlaying = true, focusPaused = false)
    assertEquals(0.25f, fade.multiplier(), 0.001f)

    fade.samplePlayer(5_200, isPlaying = false, focusPaused = false)
    elapsedMs += 10_000
    fade.samplePlayer(5_200, isPlaying = false, focusPaused = false)
    assertEquals(0.25f, fade.multiplier(), 0.001f)

    fade.samplePlayer(5_500, isPlaying = true, focusPaused = false)
    elapsedMs += 5_000
    fade.samplePlayer(10_500, isPlaying = true, focusPaused = true)
    assertEquals(0.5f, fade.multiplier(), 0.001f)
    elapsedMs += 10_000
    fade.samplePlayer(10_500, isPlaying = false, focusPaused = true)
    assertEquals(0.5f, fade.multiplier(), 0.001f)
  }

  @Test fun fallbackRetainsFadeTimeAlreadyHeard() {
    fade.start(20)
    fade.samplePlayer(200, isPlaying = true, focusPaused = false)
    elapsedMs += 5_000
    fade.samplePlayer(5_200, isPlaying = true, focusPaused = false)
    fade.sourceChanged()
    elapsedMs += 10_000
    fade.sampleTone(isPlaying = true, focusPaused = false)
    assertEquals(0.25f, fade.multiplier(), 0.001f)
    elapsedMs += 5_000
    fade.sampleTone(isPlaying = true, focusPaused = false)
    assertEquals(0.5f, fade.multiplier(), 0.001f)
  }
}
