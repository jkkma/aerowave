package com.aerowave.audio

/** Only a recently advancing decoder can become the audible alarm source. */
internal class AlarmPrewarmProgress(
  private val elapsedRealtimeMs: () -> Long,
) {
  private var lastPositionMs = 0L
  private var lastAdvanceMs = -1L

  fun reset(positionMs: Long = 0L) {
    lastPositionMs = positionMs.coerceAtLeast(0)
    lastAdvanceMs = -1L
  }

  fun sample(positionMs: Long, isPlaying: Boolean) {
    val position = positionMs.coerceAtLeast(0)
    if (isPlaying && (position > lastPositionMs + 50 || position + 250 < lastPositionMs)) {
      lastAdvanceMs = elapsedRealtimeMs()
    }
    lastPositionMs = position
  }

  fun isReady(isPlaying: Boolean): Boolean =
    isPlaying && lastAdvanceMs >= 0 && elapsedRealtimeMs() - lastAdvanceMs <= 3_000L
}
