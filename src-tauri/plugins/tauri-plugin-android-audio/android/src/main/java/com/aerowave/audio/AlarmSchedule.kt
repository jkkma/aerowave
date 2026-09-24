package com.aerowave.audio

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal object AlarmSchedule {
  const val MISSED_WINDOW_MS = 15 * 60 * 1000L

  /** Next local calendar occurrence strictly after [afterMs]. */
  fun nextAt(alarm: NativeAlarm, afterMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val after = Instant.ofEpochMilli(afterMs).atZone(zone)
    for (ahead in 0..14L) {
      val date = after.toLocalDate().plusDays(ahead)
      if (alarm.days.isNotEmpty() && alarm.skipDate == date.toString()) continue
      val candidate = atOnDate(alarm, date, zone) ?: continue
      if (candidate > afterMs) return candidate
    }
    return null
  }

  /** The one wall-clock occurrence for this local date, including DST gaps and overlaps. */
  fun atOnDate(alarm: NativeAlarm, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val weekday = date.dayOfWeek.value - 1
    if (alarm.days.isNotEmpty() && weekday !in alarm.days) return null
    val local = LocalDateTime.of(date, java.time.LocalTime.of(alarm.hour, alarm.minute))
    val rules = zone.rules
    val offsets = rules.getValidOffsets(local)
    return when {
      offsets.isNotEmpty() -> ZonedDateTime.ofLocal(local, zone, offsets.first())
      else -> {
        // A clock spring-forward can remove the requested wall time. Ring at
        // the first valid local instant rather than silently losing a user's alarm.
        val transition = rules.getTransition(local) ?: return null
        transition.dateTimeAfter.atZone(zone)
      }
    }.toInstant().toEpochMilli()
  }

  fun isDeliverable(expectedAtMs: Long, nowMs: Long): Boolean =
    nowMs >= expectedAtMs && nowMs - expectedAtMs <= MISSED_WINDOW_MS

  fun occurrenceId(alarmId: String, atMs: Long, snoozed: Boolean): String =
    "$alarmId:$atMs:${if (snoozed) "snooze" else "scheduled"}"
}
