package com.aerowave.audio

import java.io.ByteArrayInputStream
import java.nio.charset.MalformedInputException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupRestoreTest {
  private fun alarm(enabled: Boolean = false, skipDate: String? = null) = NativeAlarm(
    id = "wake", label = "Wake", hour = 7, minute = 30, days = listOf(1),
    enabled = enabled, source = NativeAlarmSource.Station("station"), volume = 0.8f,
    fadeSecs = 20, snoozeMins = 10, autoStopMins = 30, autoSnoozes = 0,
    skipDate = skipDate,
  )

  @Test fun restoreReplacesOldScheduleAtRevision() {
    val old = PersistedAlarmState(
      initialized = true, revision = 9, alarms = listOf(alarm(enabled = true)),
      scheduled = mapOf("wake" to ScheduledOccurrence("wake", "old", 123L, false)),
    )
    val changed = AlarmStateTransitions.restore(
      old, listOf(alarm()), emptyList(), null, 9, null,
    )
    assertEquals(10L, changed.revision)
    assertEquals(false, changed.alarms.single().enabled)
    assertTrue(changed.scheduled.isEmpty())
    assertTrue(changed.snoozes.isEmpty())
  }

  @Test fun restoreRejectsActivityAndUnsafeImports() {
    val old = PersistedAlarmState(revision = 3)
    val cases = listOf(
      { AlarmStateTransitions.restore(old, listOf(alarm(enabled = true)), emptyList(), null, 3, null) },
      { AlarmStateTransitions.restore(old, listOf(alarm(skipDate = "2026-09-25")), emptyList(), null, 3, null) },
      { AlarmStateTransitions.restore(old, listOf(alarm()), emptyList(), null, 2, null) },
      { AlarmStateTransitions.restore(old, listOf(alarm()), emptyList(), null, 3, "pending") },
      { AlarmStateTransitions.restore(old.copy(snoozes = mapOf("wake" to
        ScheduledOccurrence("wake", "snooze", 123L, true))),
        listOf(alarm()), emptyList(), null, 3, null) },
    )
    cases.forEach { operation ->
      try {
        operation()
        fail("unsafe restore was accepted")
      } catch (_: IllegalArgumentException) {
        // Validation must happen before persistence.
      }
    }
  }

  @Test fun readUtf8BoundsAndRejectsMalformedText() {
    assertEquals("ñ", BackupDocuments.readUtf8(ByteArrayInputStream("ñ".toByteArray())))
    assertEquals(BackupDocuments.MAX_BYTES,
      BackupDocuments.readUtf8(ByteArrayInputStream(ByteArray(BackupDocuments.MAX_BYTES) { 65 })).length)
    try {
      BackupDocuments.readUtf8(ByteArrayInputStream(ByteArray(BackupDocuments.MAX_BYTES + 1)))
      fail("oversized backup was accepted")
    } catch (_: IllegalArgumentException) { }
    try {
      BackupDocuments.readUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
      fail("malformed UTF-8 was accepted")
    } catch (_: MalformedInputException) { }
    assertEquals("ñ", BackupDocuments.readUtf8(ByteArrayInputStream(BackupDocuments.encodeUtf8("ñ"))))
    try {
      BackupDocuments.encodeUtf8("\ud800")
      fail("malformed UTF-16 was accepted")
    } catch (_: MalformedInputException) { }
  }
}
