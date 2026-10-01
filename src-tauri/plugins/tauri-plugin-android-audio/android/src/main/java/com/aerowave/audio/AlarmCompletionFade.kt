package com.aerowave.audio

/** One occurrence's completion envelope; a newer intent starts from its remaining gain. */
internal class AlarmCompletionFade(private val elapsedRealtimeMs: () -> Long) {
  data class Action(val occurrenceId: String, val snooze: Boolean, val automatic: Boolean, val origin: String)
  enum class Decision { STARTED, DUPLICATE, IGNORED }

  var action: Action? = null
    private set
  private var startedMs = 0L
  var durationMs = 0L
    private set
  private var initialGain = 0f

  fun request(next: Action, outputGain: Float, audible: Boolean): Decision {
    val previous = action
    if (previous?.occurrenceId == next.occurrenceId) {
      if (!previous.automatic && next.automatic) return Decision.IGNORED
      if (previous.snooze == next.snooze && previous.automatic == next.automatic) return Decision.DUPLICATE
    }
    val remaining = if (previous?.occurrenceId == next.occurrenceId) minOf(gain(), outputGain) else outputGain
    action = next
    initialGain = remaining.coerceIn(0f, 1f)
    startedMs = elapsedRealtimeMs()
    durationMs = if (next.automatic && audible && initialGain > 0f) 6_000L else 0L
    return Decision.STARTED
  }

  fun gain(): Float {
    if (action == null || durationMs <= 0L) return 0f
    val elapsed = (elapsedRealtimeMs() - startedMs).coerceAtLeast(0L)
    return initialGain * (1f - elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
  }

  fun isComplete(): Boolean = action != null && (durationMs <= 0L || elapsedRealtimeMs() - startedMs >= durationMs)

  fun elapsedMs(): Long = (elapsedRealtimeMs() - startedMs).coerceAtLeast(0L)

  fun clear() { action = null }
}
