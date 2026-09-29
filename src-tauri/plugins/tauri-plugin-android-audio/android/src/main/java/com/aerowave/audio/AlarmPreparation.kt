package com.aerowave.audio

import android.content.Context

/** Opportunistic station preparation. The AlarmClock delivery remains the only claim path. */
internal object AlarmPreparation {
  const val LEAD_MS = 60_000L
  private const val CLOCK_JITTER_MS = 5_000L
  const val MIN_USEFUL_LEAD_MS = 15_000L
  const val CLAIM_GRACE_MS = 15_000L

  data class Candidate(
    val alarmId: String,
    val occurrenceId: String,
    val atMs: Long,
    val snoozed: Boolean,
    val stationId: String,
    val stationName: String,
    val stationUrl: String,
    val heldUri: String?,
    val heldIsHls: Boolean,
  )

  /** Only one upcoming station gets a preparation alarm. Folder and held fallback sources stay cold. */
  fun next(state: PersistedAlarmState, nowMs: Long): Candidate? {
    if (state.ringing != null) return null
    return (state.scheduled.values + state.snoozes.values).asSequence()
      .filter { it.atMs > nowMs && it.atMs - nowMs >= MIN_USEFUL_LEAD_MS }
      .mapNotNull { candidateFor(state, it) }
      .minWithOrNull(compareBy<Candidate> { it.atMs }.thenBy { it.alarmId }.thenBy { it.snoozed })
  }

  fun scheduledAt(candidate: Candidate, nowMs: Long): Long =
    maxOf(nowMs, candidate.atMs - LEAD_MS)

  /** Called by the broadcast receiver; a stale or early delivery does not start a service. */
  fun candidate(
    context: Context,
    alarmId: String,
    occurrenceId: String,
    expectedAtMs: Long,
    snoozed: Boolean,
  ): Candidate? {
    if (!AndroidAlarmScheduler.canScheduleExact(context)) return null
    if (AlarmPlaybackService.activeOccurrenceId() != null) return null
    val nowMs = System.currentTimeMillis()
    val current = next(AlarmStateStore.snapshot(context), nowMs) ?: return null
    if (current.alarmId != alarmId || current.occurrenceId != occurrenceId ||
      current.atMs != expectedAtMs || current.snoozed != snoozed ||
      nowMs < current.atMs - LEAD_MS) return null
    return current
  }

  /** Rechecks saved identity and source while the muted player is preparing. */
  fun stillCurrent(context: Context, candidate: Candidate): Boolean {
    if (!AndroidAlarmScheduler.canScheduleExact(context)) return false
    if (AlarmPlaybackService.activeOccurrenceId() != null) return false
    return stillCurrent(AlarmStateStore.snapshot(context), candidate, System.currentTimeMillis())
  }

  fun stillCurrent(state: PersistedAlarmState, candidate: Candidate, nowMs: Long): Boolean {
    // A clock moved backwards must not keep a muted decoder active until the distant due time.
    if (state.ringing != null || nowMs < candidate.atMs - LEAD_MS - CLOCK_JITTER_MS ||
      candidate.atMs <= nowMs) return false
    val stored = (if (candidate.snoozed) state.snoozes else state.scheduled)[candidate.alarmId]
      ?: return false
    return candidateFor(state, stored) == candidate
  }

  /** Keep a due player only while its unclaimed clock or matching claim still exists. */
  fun eligibleDuringClaim(context: Context, candidate: Candidate): Boolean {
    if (!AndroidAlarmScheduler.canScheduleExact(context)) return false
    return eligibleDuringClaim(
      AlarmStateStore.snapshot(context), candidate, System.currentTimeMillis(),
      AlarmPlaybackService.activeOccurrenceId(),
    )
  }

  fun eligibleDuringClaim(
    state: PersistedAlarmState,
    candidate: Candidate,
    nowMs: Long,
    activeOccurrenceId: String?,
  ): Boolean {
    if (nowMs < candidate.atMs || nowMs - candidate.atMs > CLAIM_GRACE_MS ||
      (activeOccurrenceId != null && activeOccurrenceId != candidate.occurrenceId)) return false
    state.ringing?.let { return matchesClaim(state, candidate, it, nowMs) }
    val stored = (if (candidate.snoozed) state.snoozes else state.scheduled)[candidate.alarmId]
      ?: return false
    return candidateFor(state, stored) == candidate
  }

  /** A due-time claim may disable a one-shot and remove its scheduled record. */
  fun matchesClaim(context: Context, candidate: Candidate, ring: RingingRecord): Boolean =
    matchesClaim(AlarmStateStore.snapshot(context), candidate, ring, System.currentTimeMillis())

  fun matchesClaim(
    state: PersistedAlarmState,
    candidate: Candidate,
    ring: RingingRecord,
    nowMs: Long,
  ): Boolean {
    if (nowMs < candidate.atMs || nowMs - candidate.atMs > CLAIM_GRACE_MS ||
      state.ringing?.occurrenceId != ring.occurrenceId ||
      ring.occurrenceId != candidate.occurrenceId || ring.alarm.id != candidate.alarmId ||
      (ring.alarm.source as? NativeAlarmSource.Station)?.stationId != candidate.stationId ||
      ring.sourceUri != candidate.heldUri || ring.sourceIsHls != candidate.heldIsHls ||
      (candidate.heldUri != null && ring.sourceKind != "station")) return false
    val station = state.stations.find { it.id == candidate.stationId } ?: return false
    return station.name == candidate.stationName && station.url == candidate.stationUrl
  }

  private fun candidateFor(state: PersistedAlarmState, occurrence: ScheduledOccurrence): Candidate? {
    val alarm = state.alarms.find { it.id == occurrence.alarmId } ?: return null
    if ((!occurrence.snoozed || occurrence.deferredFromScheduled) && !alarm.enabled) return null
    if (!occurrence.snoozed && AlarmStateTransitions.isSkipped(alarm, occurrence.atMs)) return null
    val source = alarm.source as? NativeAlarmSource.Station ?: return null
    // A snooze retains the chosen backup or system tone instead of retrying its original station.
    if (occurrence.snoozed && occurrence.heldKind != null && occurrence.heldKind != "station") return null
    if (occurrence.snoozed && occurrence.heldUri != null && occurrence.heldKind != "station") return null
    val station = state.stations.find { it.id == source.stationId && it.url.isNotBlank() }
      ?: return null
    return Candidate(
      alarm.id, occurrence.occurrenceId, occurrence.atMs, occurrence.snoozed,
      station.id, station.name, station.url,
      occurrence.heldUri, occurrence.heldIsHls,
    )
  }
}
