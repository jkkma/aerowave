package com.aerowave.audio

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
