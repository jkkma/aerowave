package com.aerowave.audio

/** Measures fade time only while the alarm source is producing playable audio. */
internal class AlarmFadeProgress(
  private val elapsedRealtimeMs: () -> Long,
) {
  private var durationMs = 0L
  private var audibleMs = 0L
  private var audibleSinceMs: Long? = null
  private var lastPositionMs = 0L

  fun start(fadeSecs: Int) {
    durationMs = fadeSecs.coerceAtLeast(0) * 1_000L
    audibleMs = 0L
    audibleSinceMs = null
    lastPositionMs = 0L
  }

  fun sourceChanged() {
    pause()
    lastPositionMs = 0L
  }

  fun pause() {
    audibleSinceMs?.let { since ->
      audibleMs += (elapsedRealtimeMs() - since).coerceAtLeast(0)
      audibleSinceMs = null
    }
  }

  fun samplePlayer(positionMs: Long, isPlaying: Boolean, focusPaused: Boolean) {
    val position = positionMs.coerceAtLeast(0)
    val advanced = position > lastPositionMs + 50 || position + 250 < lastPositionMs
    lastPositionMs = position
    setAudible(isPlaying && !focusPaused && advanced)
  }

  fun sampleTone(isPlaying: Boolean, focusPaused: Boolean) {
    setAudible(isPlaying && !focusPaused)
  }

  fun multiplier(): Float {
    if (durationMs <= 0L) return 1f
    val elapsed = audibleMs + (audibleSinceMs?.let {
      (elapsedRealtimeMs() - it).coerceAtLeast(0)
    } ?: 0L)
    return (elapsed.toFloat() / durationMs).coerceIn(0.02f, 1f)
  }

  fun isComplete(): Boolean = durationMs <= 0L || multiplier() >= 1f

  private fun setAudible(audible: Boolean) {
    if (audible) {
      if (audibleSinceMs == null) audibleSinceMs = elapsedRealtimeMs()
    } else {
      pause()
    }
  }
}
