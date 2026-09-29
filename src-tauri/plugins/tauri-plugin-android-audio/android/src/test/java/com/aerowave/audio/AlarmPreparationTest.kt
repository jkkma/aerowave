package com.aerowave.audio

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPreparationTest {
  private val now = 1_800_000_000_000L

  private fun alarm(
    id: String = "wake",
    enabled: Boolean = true,
    source: NativeAlarmSource = NativeAlarmSource.Station("jazz"),
    skipDate: String? = null,
  ) = NativeAlarm(
    id, "Wake", 7, 0, listOf(0), enabled, source,
    0.8f, 20, 10, 30, 1, skipDate,
  )

  private fun occurrence(
    alarmId: String = "wake",
    atMs: Long = now + 120_000,
    snoozed: Boolean = false,
    heldUri: String? = null,
    heldKind: String? = null,
  ) = ScheduledOccurrence(
    alarmId, AlarmSchedule.occurrenceId(alarmId, atMs, snoozed), atMs, snoozed,
    heldUri = heldUri, heldKind = heldKind,
  )

  private fun state(
    alarms: List<NativeAlarm> = listOf(alarm()),
    regular: List<ScheduledOccurrence> = listOf(occurrence()),
    snoozes: List<ScheduledOccurrence> = emptyList(),
    stations: List<NativeStation> = listOf(NativeStation("jazz", "Jazz Fusion", "http://example.com/live")),
  ) = PersistedAlarmState(
    initialized = true, alarms = alarms, stations = stations,
    scheduled = regular.associateBy { it.alarmId },
    snoozes = snoozes.associateBy { it.alarmId },
  )

  @Test fun choosesEarliestUsefulStationAndPreservesDueTime() {
    val folder = alarm("folder", source = NativeAlarmSource.Folder("content://music"))
    val early = occurrence("folder", now + 30_000)
    val later = occurrence("wake", now + 90_000)
    val candidate = AlarmPreparation.next(
      state(alarms = listOf(folder, alarm()), regular = listOf(early, later)), now,
    )!!
    assertEquals("wake", candidate.alarmId)
    assertEquals(later.atMs, candidate.atMs)
    assertEquals(later.atMs - 60_000, AlarmPreparation.scheduledAt(candidate, now))
  }

  @Test fun imminentOccurrenceIsSkippedWithoutMovingTheFireTime() {
    val tooClose = occurrence(atMs = now + 14_999)
    assertNull(AlarmPreparation.next(state(regular = listOf(tooClose)), now))
    val close = occurrence(atMs = now + 30_000)
    val candidate = AlarmPreparation.next(state(regular = listOf(close)), now)!!
    assertEquals(now, AlarmPreparation.scheduledAt(candidate, now))
    assertEquals(close.atMs, candidate.atMs)
  }

  @Test fun disabledSkippedOrMissingStationCannotPrepare() {
    val occurrence = occurrence()
    assertNull(AlarmPreparation.next(state(alarms = listOf(alarm(enabled = false))), now))
    assertNull(AlarmPreparation.next(state(stations = emptyList()), now))
    val date = Instant.ofEpochMilli(occurrence.atMs).atZone(ZoneId.systemDefault())
      .toLocalDate().toString()
    assertNull(AlarmPreparation.next(state(alarms = listOf(alarm(skipDate = date))), now))
  }

  @Test fun disabledOneShotSnoozeCanPrepareItsHeldStationButNotHeldFallback() {
    val held = occurrence(atMs = now + 90_000, snoozed = true,
      heldUri = "http://example.com/redirected", heldKind = "station")
    val oneShot = alarm(enabled = false).copy(days = emptyList())
    val valid = AlarmPreparation.next(state(
      alarms = listOf(oneShot), regular = emptyList(), snoozes = listOf(held),
    ), now)!!
    assertTrue(valid.snoozed)
    assertEquals(held.heldUri, valid.heldUri)
    assertNull(AlarmPreparation.next(state(
      alarms = listOf(oneShot), regular = emptyList(),
      snoozes = listOf(held.copy(heldKind = "backup")),
    ), now))
    assertNull(AlarmPreparation.next(state(
      alarms = listOf(oneShot), regular = emptyList(),
      snoozes = listOf(held.copy(heldKind = "tone")),
    ), now))
  }

  @Test fun staleOccurrenceOrEditedStationInvalidatesPreparation() {
    val original = state()
    val candidate = AlarmPreparation.next(original, now)!!
    assertTrue(AlarmPreparation.stillCurrent(original, candidate, candidate.atMs - 60_000))
    assertFalse(AlarmPreparation.stillCurrent(original, candidate, candidate.atMs - 70_000))
    assertFalse(AlarmPreparation.stillCurrent(original, candidate, candidate.atMs))
    assertFalse(AlarmPreparation.stillCurrent(original.copy(
      scheduled = mapOf("wake" to occurrence(atMs = now + 180_000)),
    ), candidate, candidate.atMs - 60_000))
    assertFalse(AlarmPreparation.stillCurrent(original.copy(
      stations = listOf(NativeStation("jazz", "Jazz Fusion", "http://example.com/new")),
    ), candidate, candidate.atMs - 60_000))
    assertFalse(AlarmPreparation.stillCurrent(original.copy(
      alarms = listOf(alarm(source = NativeAlarmSource.Folder("content://music"))),
    ), candidate, candidate.atMs - 60_000))
  }

  @Test fun dueClaimMatchesEvenWhenOneShotIsConsumed() {
    val oneShot = alarm().copy(days = emptyList())
    val before = state(alarms = listOf(oneShot))
    val candidate = AlarmPreparation.next(before, now)!!
    val ring = RingingRecord(
      alarm = oneShot.copy(enabled = false), occurrenceId = candidate.occurrenceId,
      trigger = "scheduled", startedAtMs = candidate.atMs,
      startedElapsedMs = 5_000,
    )
    val claimed = before.copy(alarms = listOf(ring.alarm), scheduled = emptyMap(), ringing = ring)
    assertTrue(AlarmPreparation.matchesClaim(claimed, candidate, ring, candidate.atMs))
    assertTrue(AlarmPreparation.matchesClaim(claimed, candidate, ring, candidate.atMs + 1_000))
    assertFalse(AlarmPreparation.matchesClaim(
      claimed, candidate, ring, candidate.atMs + AlarmPreparation.CLAIM_GRACE_MS + 1,
    ))
    assertFalse(AlarmPreparation.matchesClaim(claimed.copy(stations = listOf(
      NativeStation("jazz", "Jazz Fusion", "http://example.com/changed"),
    )), candidate, ring, candidate.atMs))
    assertFalse(AlarmPreparation.matchesClaim(claimed, candidate.copy(
      occurrenceId = "wrong",
    ), ring, candidate.atMs))
    assertFalse(AlarmPreparation.matchesClaim(claimed, candidate, ring.copy(
      sourceUri = "http://example.com/other",
    ), candidate.atMs))
  }

  @Test fun dueGraceRequiresTheSameUnclaimedOccurrenceAndSource() {
    val original = state()
    val candidate = AlarmPreparation.next(original, now)!!
    val due = candidate.atMs
    assertFalse(AlarmPreparation.eligibleDuringClaim(original, candidate, due - 1, null))
    assertTrue(AlarmPreparation.eligibleDuringClaim(original, candidate, due, null))
    assertTrue(AlarmPreparation.eligibleDuringClaim(
      original, candidate, due + AlarmPreparation.CLAIM_GRACE_MS, candidate.occurrenceId,
    ))
    assertFalse(AlarmPreparation.eligibleDuringClaim(
      original, candidate, due + AlarmPreparation.CLAIM_GRACE_MS + 1, null,
    ))
    assertFalse(AlarmPreparation.eligibleDuringClaim(original, candidate, due, "other:due:scheduled"))
    assertFalse(AlarmPreparation.eligibleDuringClaim(
      original.copy(scheduled = emptyMap()), candidate, due, null,
    ))
    assertFalse(AlarmPreparation.eligibleDuringClaim(original.copy(
      scheduled = mapOf("wake" to occurrence(atMs = due + 60_000)),
    ), candidate, due, null))
    assertFalse(AlarmPreparation.eligibleDuringClaim(original.copy(
      stations = listOf(NativeStation("jazz", "Jazz Fusion", "http://example.com/edited")),
    ), candidate, due, null))
  }

  @Test fun dueGraceRetainsOnlyMatchingClaimIncludingHeldStationSnooze() {
    val held = occurrence(atMs = now + 90_000, snoozed = true,
      heldUri = "http://example.com/redirected", heldKind = "station")
    val oneShot = alarm(enabled = false).copy(days = emptyList())
    val before = state(alarms = listOf(oneShot), regular = emptyList(), snoozes = listOf(held))
    val candidate = AlarmPreparation.next(before, now)!!
    assertTrue(AlarmPreparation.eligibleDuringClaim(before, candidate, candidate.atMs, null))
    val ring = RingingRecord(
      alarm = oneShot, occurrenceId = candidate.occurrenceId,
      trigger = "snooze", startedAtMs = candidate.atMs, startedElapsedMs = 5_000,
      sourceKind = "station", sourceUri = held.heldUri,
    )
    val claimed = before.copy(snoozes = emptyMap(), ringing = ring)
    assertTrue(AlarmPreparation.eligibleDuringClaim(
      claimed, candidate, candidate.atMs + 1_000, candidate.occurrenceId,
    ))
    assertFalse(AlarmPreparation.eligibleDuringClaim(
      claimed, candidate, candidate.atMs + 1_000, "other:due:snooze",
    ))
    assertFalse(AlarmPreparation.eligibleDuringClaim(claimed.copy(
      ringing = ring.copy(occurrenceId = "other:due:snooze"),
    ), candidate, candidate.atMs + 1_000, null))
    assertFalse(AlarmPreparation.eligibleDuringClaim(claimed.copy(
      stations = listOf(NativeStation("jazz", "Jazz Fusion", "http://example.com/edited")),
    ), candidate, candidate.atMs + 1_000, candidate.occurrenceId))
  }

  @Test fun nextStationPreparesOnlyAfterThePreviousRingIsCleared() {
    val first = occurrence("wake", now + 30_000)
    val second = occurrence("later", now + 120_000)
    val before = state(
      alarms = listOf(alarm(), alarm("later")), regular = listOf(first, second),
    )
    val activeRing = RingingRecord(
      alarm = alarm(), occurrenceId = first.occurrenceId,
      trigger = "scheduled", startedAtMs = first.atMs, startedElapsedMs = 5_000,
    )
    val ringing = before.copy(scheduled = mapOf("later" to second), ringing = activeRing)
    assertNull(AlarmPreparation.next(ringing, now))
    val next = AlarmPreparation.next(ringing.copy(ringing = null), now)!!
    assertEquals(second.occurrenceId, next.occurrenceId)
    assertEquals(second.atMs - AlarmPreparation.LEAD_MS,
      AlarmPreparation.scheduledAt(next, now))
  }
}
