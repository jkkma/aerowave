package com.aerowave.audio

/** The alarm source's startup and stall limits run only while audio focus is available. */
internal class AlarmPlaybackProgress(
  private val elapsedRealtimeMs: () -> Long,
) {
  private var sourceStartedMs = 0L
  private var lastProgressMs = 0L
  private var lastPositionMs = 0L
  private var decodedProgress = false
  private var focusPaused = false

  fun start(pausedForFocus: Boolean) {
    val now = elapsedRealtimeMs()
    sourceStartedMs = now
    lastProgressMs = now
    lastPositionMs = 0
    decodedProgress = false
    focusPaused = pausedForFocus
  }

  fun pauseForFocus() {
    focusPaused = true
  }

  fun resumeFromFocus(positionMs: Long) {
    if (!focusPaused) return
    val now = elapsedRealtimeMs()
    // A resumed decoder needs a full startup/stall interval before fallback.
    sourceStartedMs = now
    lastProgressMs = now
    lastPositionMs = positionMs.coerceAtLeast(0)
    focusPaused = false
  }

  fun failureReason(
    positionMs: Long,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    isStation: Boolean,
  ): String? {
    if (focusPaused) return null
    val now = elapsedRealtimeMs()
    val position = positionMs.coerceAtLeast(0)
    if (isPlaying && (position > lastPositionMs + 50 || position + 250 < lastPositionMs)) {
      decodedProgress = true
      lastProgressMs = now
    }
    lastPositionMs = position
    val startLimit = if (isStation) STATION_START_TIMEOUT_MS else LOCAL_START_TIMEOUT_MS
    if (!decodedProgress && now - sourceStartedMs >= startLimit) {
      return "The alarm source connected but did not produce audio"
    }
    if (decodedProgress && playWhenReady && now - lastProgressMs >= STALL_TIMEOUT_MS) {
      return "The alarm source stopped producing audio"
    }
    return null
  }

  companion object {
    private const val STATION_START_TIMEOUT_MS = 12_000L
    private const val LOCAL_START_TIMEOUT_MS = 8_000L
    private const val STALL_TIMEOUT_MS = 12_000L
  }
}
