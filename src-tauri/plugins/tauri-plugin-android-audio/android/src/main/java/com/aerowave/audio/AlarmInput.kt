package com.aerowave.audio

import kotlin.math.sqrt

/** A release belongs to the same press and ring that received its first DOWN. */
class AlarmInputGate {
  private var occurrenceId: String? = null
  private var pressedKey: Int? = null
  private var pressedOccurrence: String? = null
  private var actionStarted = false

  fun bind(occurrence: String?) {
    if (occurrenceId == occurrence) return
    occurrenceId = occurrence
    pressedKey = null
    pressedOccurrence = null
    actionStarted = false
  }

  fun volumeDown(keyCode: Int, repeatCount: Int, activeOccurrence: String): Boolean {
    if (occurrenceId != activeOccurrence || actionStarted) return false
    if (repeatCount == 0) {
      pressedKey = keyCode
      pressedOccurrence = activeOccurrence
    }
    return true
  }

  fun volumeUp(keyCode: Int, activeOccurrence: String): Boolean {
    val matches = occurrenceId == activeOccurrence && !actionStarted &&
      pressedKey == keyCode && pressedOccurrence == activeOccurrence
    pressedKey = null
    pressedOccurrence = null
    return matches
  }

  fun beginAction(activeOccurrence: String): Boolean {
    if (occurrenceId != activeOccurrence || actionStarted) return false
    actionStarted = true
    pressedKey = null
    pressedOccurrence = null
    return true
  }

  fun actionFailed(activeOccurrence: String) {
    if (occurrenceId == activeOccurrence) actionStarted = false
  }
}

/** Require several strong changes in opposite directions; a single jolt is ignored. */
internal class AlarmShakeDetector {
  private var inPeak = false
  private var firstPeakMs = 0L
  private var lastPeakMs = 0L
  private var lastX = 0f
  private var lastY = 0f
  private var lastZ = 0f
  private var lastMagnitude = 0f
  private var peakCount = 0

  fun reset() {
    inPeak = false
    peakCount = 0
  }

  fun sample(x: Float, y: Float, z: Float, elapsedMs: Long): Boolean {
    val magnitude = sqrt(x * x + y * y + z * z)
    if (magnitude < RESET_METERS_PER_SECOND_SQUARED) {
      inPeak = false
      return false
    }
    if (magnitude < PEAK_METERS_PER_SECOND_SQUARED || inPeak) return false
    inPeak = true

    val gap = elapsedMs - lastPeakMs
    if (peakCount == 0 || gap > MAX_PEAK_GAP_MS || elapsedMs - firstPeakMs > MAX_SEQUENCE_MS) {
      startSequence(x, y, z, magnitude, elapsedMs)
      return false
    }
    if (gap < MIN_PEAK_GAP_MS) return false
    val dot = x * lastX + y * lastY + z * lastZ
    if (dot > -MIN_REVERSAL_COSINE * magnitude * lastMagnitude) {
      startSequence(x, y, z, magnitude, elapsedMs)
      return false
    }
    peakCount++
    lastPeakMs = elapsedMs
    lastX = x
    lastY = y
    lastZ = z
    lastMagnitude = magnitude
    if (peakCount < REQUIRED_PEAKS) return false
    reset()
    return true
  }

  private fun startSequence(x: Float, y: Float, z: Float, magnitude: Float, elapsedMs: Long) {
    firstPeakMs = elapsedMs
    lastPeakMs = elapsedMs
    lastX = x
    lastY = y
    lastZ = z
    lastMagnitude = magnitude
    peakCount = 1
  }

  companion object {
    private const val PEAK_METERS_PER_SECOND_SQUARED = 11.5f
    private const val RESET_METERS_PER_SECOND_SQUARED = 4f
    private const val MIN_REVERSAL_COSINE = 0.35f
    private const val MIN_PEAK_GAP_MS = 120L
    private const val MAX_PEAK_GAP_MS = 850L
    private const val MAX_SEQUENCE_MS = 2_400L
    private const val REQUIRED_PEAKS = 4
  }
}
