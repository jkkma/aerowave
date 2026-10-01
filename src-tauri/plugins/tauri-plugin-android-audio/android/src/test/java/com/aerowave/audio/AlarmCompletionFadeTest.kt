package com.aerowave.audio

import org.junit.Assert.*
import org.junit.Test

class AlarmCompletionFadeTest {
  @Test fun automaticEnvelopeUsesElapsedTimeAndReachesZeroAtSixSeconds() {
    var now = 10_000L
    val fade = AlarmCompletionFade { now }
    val action = AlarmCompletionFade.Action("ring", false, true, "automatic_timeout")
    assertEquals(AlarmCompletionFade.Decision.STARTED, fade.request(action, 0.6f, true))
    assertEquals(0.6f, fade.gain(), 0.0001f)
    now += 1_500
    assertEquals(0.45f, fade.gain(), 0.0001f)
    now += 3_500
    assertEquals(0.1f, fade.gain(), 0.0001f)
    assertFalse(fade.isComplete())
    now += 1_000
    assertEquals(0f, fade.gain(), 0.0001f)
    assertTrue(fade.isComplete())
  }

  @Test fun duplicateAutomaticActionsCannotRestartOrExtendTheDeadline() {
    var now = 0L
    val fade = AlarmCompletionFade { now }
    val action = AlarmCompletionFade.Action("ring", true, true, "automatic_timeout")
    fade.request(action, 0.8f, true)
    now = 5_000
    assertEquals(AlarmCompletionFade.Decision.DUPLICATE, fade.request(action, 0.8f, true))
    assertEquals(0.8f / 6f, fade.gain(), 0.0001f)
    now = 6_000
    assertTrue(fade.isComplete())
  }

  @Test fun manualIntentOverridesAutomaticAndCompletesWithoutAFade() {
    var now = 0L
    val fade = AlarmCompletionFade { now }
    fade.request(AlarmCompletionFade.Action("ring", true, true, "automatic_timeout"), 0.6f, true)
    now = 2_000
    val manual = AlarmCompletionFade.Action("ring", false, false, "native_screen")
    assertEquals(AlarmCompletionFade.Decision.STARTED, fade.request(manual, 0.4f, true))
    assertEquals(manual, fade.action)
    assertTrue(fade.isComplete())
    assertEquals(0L, fade.durationMs)
    assertEquals(AlarmCompletionFade.Decision.IGNORED,
      fade.request(AlarmCompletionFade.Action("ring", true, true, "automatic_timeout"), 0.6f, true))
  }

  @Test fun silentSourceDoesNotDelayCompletionAndClearedFadeCannotFinishLater() {
    val fade = AlarmCompletionFade { 50L }
    fade.request(AlarmCompletionFade.Action("ring", false, true, "automatic_timeout"), 0.6f, false)
    assertTrue(fade.isComplete())
    fade.clear()
    assertNull(fade.action)
    assertFalse(fade.isComplete())
    fade.request(AlarmCompletionFade.Action("zero", false, true, "automatic_timeout"), 0f, true)
    assertTrue(fade.isComplete())
  }
}
