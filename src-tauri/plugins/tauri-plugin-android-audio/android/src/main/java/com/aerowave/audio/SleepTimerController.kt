package com.aerowave.audio

internal const val SLEEP_OUTCOME_CANCELLED = "cancelled"
internal const val SLEEP_OUTCOME_FINISHED = "finished"

internal data class SleepTimerInfo(
  val minutes: Int,
  val endsAtMs: Long,
  val elapsedDeadlineMs: Long,
  val remainingMs: Long,
)

internal data class SleepTimerSnapshot(
  val revision: Long = 0,
  val timer: SleepTimerInfo? = null,
  val outcome: String? = null,
  val error: String? = null,
  val lastFinishedRevision: Long = 0,
)

/**
 * Owns the sleep timer's monotonic deadline and revision fence. Android wall
 * time is presentation data only: elapsed realtime remains correct across
 * clock changes and includes time spent asleep.
 */
internal class SleepTimerController(
  initial: SleepTimerSnapshot = SleepTimerSnapshot(),
  private val elapsedRealtimeMs: () -> Long,
  private val wallTimeMs: () -> Long,
) {
  private var current = initial

  fun start(minutes: Int): SleepTimerSnapshot {
    require(minutes in 1..1_440) {
      "Choose a sleep timer between 1 minute and 24 hours"
    }
    val durationMs = Math.multiplyExact(minutes.toLong(), 60_000L)
    val elapsedDeadline = Math.addExact(elapsedRealtimeMs(), durationMs)
    val wallDeadline = Math.addExact(wallTimeMs(), durationMs)
    current = current.copy(
      revision = current.revision + 1,
      timer = SleepTimerInfo(minutes, wallDeadline, elapsedDeadline, durationMs),
      outcome = null,
      error = null,
    )
    return snapshot()
  }

  fun cancel(): SleepTimerSnapshot {
    if (current.timer == null) return snapshot()
    current = current.copy(
      revision = current.revision + 1,
      timer = null,
      outcome = SLEEP_OUTCOME_CANCELLED,
      error = null,
    )
    return snapshot()
  }

  fun finishIfDue(): Boolean {
    val timer = current.timer ?: return false
    if (elapsedRealtimeMs() < timer.elapsedDeadlineMs) return false
    val finishedRevision = current.revision + 1
    current = current.copy(
      revision = finishedRevision,
      timer = null,
      outcome = SLEEP_OUTCOME_FINISHED,
      error = null,
      lastFinishedRevision = finishedRevision,
    )
    return true
  }

  fun snapshot(): SleepTimerSnapshot {
    val timer = current.timer ?: return current
    val remaining = (timer.elapsedDeadlineMs - elapsedRealtimeMs()).coerceAtLeast(0)
    return current.copy(timer = timer.copy(remainingMs = remaining))
  }

  fun fadeMultiplier(): Float {
    val remaining = snapshot().timer?.remainingMs ?: return 1f
    return (remaining.toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
  }

  fun shouldRejectPlay(capturedRevision: Long?): Boolean =
    capturedRevision != null && current.lastFinishedRevision > capturedRevision

  fun hasActiveTimer(): Boolean = current.timer != null

  companion object {
    const val FADE_DURATION_MS = 20_000f
  }
}
