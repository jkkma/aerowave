package com.aerowave.audio

/** A reconnect earns a fresh budget only after the playhead advances for real time. */
internal class SustainedMediaProgress(
  private val elapsedRealtimeMs: () -> Long,
) {
  private var lastPositionMs: Long? = null
  private var lastSampleElapsedMs: Long? = null
  private var progressedMs = 0L

  fun reset() {
    lastPositionMs = null
    lastSampleElapsedMs = null
    progressedMs = 0
  }

  fun sample(isPlaying: Boolean, positionMs: Long): Boolean {
    if (!isPlaying || positionMs < 0) {
      reset()
      return false
    }
    val now = elapsedRealtimeMs()
    val previousPosition = lastPositionMs
    val previousSample = lastSampleElapsedMs
    lastPositionMs = positionMs
    lastSampleElapsedMs = now
    if (previousPosition == null || previousSample == null) return false

    val mediaDelta = positionMs - previousPosition
    val elapsedDelta = now - previousSample
    if (mediaDelta <= 0 || elapsedDelta <= 0 || mediaDelta > MAX_CONTINUOUS_DELTA_MS) {
      progressedMs = 0
      return false
    }
    progressedMs += minOf(mediaDelta, elapsedDelta, MAX_SAMPLE_MS)
    return progressedMs >= REQUIRED_PROGRESS_MS
  }

  companion object {
    private const val REQUIRED_PROGRESS_MS = 15_000L
    private const val MAX_SAMPLE_MS = 1_000L
    private const val MAX_CONTINUOUS_DELTA_MS = 5_000L
  }
}
