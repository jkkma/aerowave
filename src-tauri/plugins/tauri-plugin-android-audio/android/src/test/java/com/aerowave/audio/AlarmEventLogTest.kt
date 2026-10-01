package com.aerowave.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlarmEventLogTest {
  // Keep fixtures as build evidence. The logger and its tests never remove files.
  private fun directory(): File = File("build/test-fixtures/alarm-event-log/${UUID.randomUUID()}")
    .absoluteFile.apply { check(mkdirs()) }

  private fun engine(directory: File, slotBytes: Int = AlarmEventLog.SLOT_BYTES) = AlarmLogEngine(
    directory = { directory }, wallMs = { 1_700_000_000_000L }, elapsedMs = { 1234L },
    zoneId = { "America/Asuncion" }, slotBytes = slotBytes,
  )

  private fun snapshot(engine: AlarmLogEngine): AlarmEventLog.Snapshot {
    val result = AtomicReference<AlarmEventLog.Snapshot>()
    val completed = CountDownLatch(1)
    engine.snapshot { result.set(it); completed.countDown() }
    assertTrue("snapshot callback did not arrive", completed.await(5, TimeUnit.SECONDS))
    return result.get()
  }

  private fun rows(snapshot: AlarmEventLog.Snapshot): List<JSONObject> =
    snapshot.jsonl.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()

  @Test fun recordsAreOrderedAndExportHasClockCorrelationAndRetentionMetadata() {
    val writer = engine(directory())
    try {
      repeat(20) { writer.record("source.progress", "scheduled:private-alarm:$it", mapOf("ordinal" to it)) }
      val exported = snapshot(writer)
      assertNull(exported.status.snapshotFailure)
      assertFalse(exported.status.partial)
      val events = rows(exported).filter { it.has("event") }
      assertEquals((0 until 20).toList(), events.filter { it.getString("event") == "source.progress" }
        .map { it.getJSONObject("fields").getInt("ordinal") })
      assertTrue(events.zipWithNext().all { (a, b) -> a.getLong("sequence") < b.getLong("sequence") })
      val event = events.first { it.getString("event") == "source.progress" }
      assertTrue(event.getString("utc").endsWith("Z"))
      assertEquals("America/Asuncion", event.getString("zone"))
      assertTrue(event.getString("local").contains("-03:00"))
      assertEquals(1234L, event.getLong("elapsedMs"))
      assertEquals(writer.sessionId, event.getString("session"))
      assertEquals(16, event.getString("occurrence").length)
      val status = events.last()
      assertEquals("export.status", status.getString("event"))
      assertEquals(4, status.getJSONObject("fields").getInt("slotCount"))
      assertEquals(4L * AlarmEventLog.SLOT_BYTES, status.getJSONObject("fields").getLong("retentionBytes"))
    } finally { writer.close() }
  }

  @Test fun labelsUrlsPathsCredentialsAndExceptionMessagesNeverReachExport() {
    val writer = engine(directory())
    val secret = "Private wake up label https://user:secret@example.com/radio?token=private /storage/private/track.mp3"
    try {
      writer.record("source.failed", secret, mapOf(
        "alarmLabel" to "Private wake up label", "streamUrl" to "https://user:secret@example.com/radio",
        "contentUri" to "content://private-provider/music", "filesystemPath" to "C:\\Users\\Private\\music.mp3",
        "password" to "secret-password", "detail" to secret,
        "currentOccurrenceId" to secret, "durationMs" to 42L,
        "errorCodeName" to "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "mimeType" to "audio/aacp",
      ), IllegalStateException(secret, IOException(secret)))
      val exported = snapshot(writer)
      val text = exported.jsonl.toString(Charsets.UTF_8)
      assertFalse(text.contains("Private wake up label"))
      assertFalse(text.contains("example.com"))
      assertFalse(text.contains("private-provider"))
      assertFalse(text.contains("secret-password"))
      assertFalse(text.contains("music.mp3"))
      val event = rows(exported).first { it.optString("event") == "source.failed" }
      val fields = event.getJSONObject("fields")
      assertEquals(42L, fields.getLong("durationMs"))
      assertEquals("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", fields.getString("errorCodeName"))
      assertEquals("audio/aacp", fields.getString("mimeType"))
      assertEquals(fingerprint(secret), fields.getString("currentOccurrenceId"))
      assertTrue(event.getJSONObject("error").getBoolean("messageOmitted"))
      assertTrue(event.getJSONObject("error").getJSONArray("types").toString().contains("IllegalStateException"))
    } finally { writer.close() }
  }

  @Test fun fixedSlotsRetainRecentEventsWithoutGrowingOrDeletingAnUnrelatedFile() {
    val directory = directory()
    val unrelated = File(directory, "unrelated.txt").apply { writeText("keep") }
    val store = AlarmLogStore(directory, 2, 512)
    repeat(100) { store.append((JSONObject().put("event", "test.row").put("ordinal", it).toString() + "\n").toByteArray()) }
    val slots = directory.listFiles()!!.filter { it.name.startsWith("events.") }
    assertEquals(2, slots.size)
    assertTrue(slots.all { it.length() <= 512 })
    assertTrue(slots.sumOf { it.length() } <= 1024)
    assertEquals("keep", unrelated.readText())
    assertTrue(store.historyTruncated)
    assertFalse(store.partial)
    val ordinals = store.snapshot().toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
      .map(::JSONObject).filter { it.has("ordinal") }.map { it.getInt("ordinal") }.toList()
    assertEquals(99, ordinals.last())
    assertTrue(ordinals.zipWithNext().all { (a, b) -> a < b })
  }

  @Test fun normalRotationHasNeutralHistoryTruncationStatus() {
    val writer = engine(directory(), slotBytes = 1024)
    try {
      repeat(24) { writer.record("test.rotation", fields = mapOf("ordinal" to it)) }
      val exported = snapshot(writer)
      assertTrue(exported.status.historyTruncated)
      assertFalse(exported.status.recoveredIncompleteData)
      assertFalse(exported.status.partial)
      assertEquals(0L, exported.status.writeFailures)
    } finally { writer.close() }
  }

  @Test fun restartRetainsOldEventsAndStartsASeparateCorrelatedSession() {
    val directory = directory()
    val first = engine(directory)
    first.record("test.before_restart", "same-occurrence")
    val before = snapshot(first)
    first.close()
    val second = engine(directory)
    try {
      second.record("test.after_restart", "same-occurrence")
      val after = snapshot(second)
      val events = rows(after)
      val old = events.first { it.optString("event") == "test.before_restart" }
      val fresh = events.first { it.optString("event") == "test.after_restart" }
      assertEquals(first.sessionId, old.getString("session"))
      assertEquals(second.sessionId, fresh.getString("session"))
      assertNotEquals(old.getString("session"), fresh.getString("session"))
      assertEquals(old.getString("occurrence"), fresh.getString("occurrence"))
      assertTrue(after.status.retainedBytes >= before.status.retainedBytes)
      assertEquals(2, events.count { it.optString("event") == "process.session_start" })
    } finally { second.close() }
  }

  @Test fun interruptedAndOversizedActiveTailAreRepairedBeforeRestartAppend() {
    for (oversized in listOf(false, true)) {
      val directory = directory()
      val store = AlarmLogStore(directory, 2, 512)
      store.append("{\"event\":\"test.before\"}\n".toByteArray())
      val active = File(directory, "events.0.jsonl")
      val prefix = active.readBytes()
      val tail = if (oversized) ByteArray(513 - prefix.size - 1) { 'x'.code.toByte() } + byteArrayOf('\n'.code.toByte())
        else "{\"event\":\"interrupted".toByteArray()
      FileOutputStream(active, true).use { it.write(tail) }
      val reopened = AlarmLogStore(directory, 2, 512)
      reopened.append("{\"event\":\"test.after\"}\n".toByteArray())
      val events = reopened.snapshot().toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
      assertTrue(events.any { it.optString("event") == "test.before" })
      assertTrue(events.any { it.optString("event") == "test.after" })
      assertTrue(reopened.recoveredIncompleteData)
      assertTrue(active.length() <= 512)
    }
  }

  @Test fun malformedCompleteRowIsOmittedFromSnapshotAndReported() {
    val directory = directory()
    val store = AlarmLogStore(directory, 2, 512)
    store.append("{\"event\":\"test.before\"}\n".toByteArray())
    FileOutputStream(File(directory, "events.0.jsonl"), true).use { it.write("broken\n".toByteArray()) }
    store.append("{\"event\":\"test.after\"}\n".toByteArray())
    val text = store.snapshot().toString(Charsets.UTF_8)
    assertFalse(text.contains("broken"))
    text.lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
    assertTrue(store.partial)
    assertTrue(store.recoveredIncompleteData)
  }

  @Test fun failedPartialAppendIsRepairedWithinTheSameProcess() {
    val directory = directory()
    val tearOnce = AtomicBoolean(true)
    val writer = AlarmLogEngine(directory = { directory }, wallMs = { 1000L }, elapsedMs = { 100L },
      appendRow = { file, bytes ->
        if (bytes.toString(Charsets.UTF_8).contains("test.torn") && tearOnce.compareAndSet(true, false)) {
          FileOutputStream(file, true).use { it.write(bytes, 0, bytes.size / 2) }
          throw IOException("synthetic interrupted append")
        }
        FileOutputStream(file, true).use { it.write(bytes) }
      })
    try {
      writer.record("test.torn")
      writer.record("test.survives")
      val exported = snapshot(writer)
      assertTrue(exported.status.writeFailures >= 1)
      assertTrue(exported.status.partial)
      assertTrue(exported.status.recoveredIncompleteData)
      val events = rows(exported)
      assertFalse(events.any { it.optString("event") == "test.torn" })
      assertTrue(events.any { it.optString("event") == "test.survives" })
      assertTrue(events.any { it.optString("event") == "logger.health" })
    } finally { writer.close() }
  }

  @Test fun storageFailureIsFailOpenAndStartupMetadataRetriesAfterRecovery() {
    val directory = directory()
    val unavailable = AtomicBoolean(true)
    val writer = AlarmLogEngine(directory = {
      if (unavailable.get()) throw IOException("synthetic unavailable storage")
      directory
    }, wallMs = { 1000L }, elapsedMs = { 100L }, sessionFields = { mapOf("versionCode" to 77L) })
    try {
      writer.record("test.while_unavailable")
      val failed = snapshot(writer)
      assertNotNull(failed.status.snapshotFailure)
      assertTrue(failed.status.writeFailures > 0)
      assertEquals(0, failed.jsonl.size)
      unavailable.set(false)
      writer.record("test.after_recovery")
      val recovered = snapshot(writer)
      assertNull(recovered.status.snapshotFailure)
      assertTrue(recovered.status.partial)
      val events = rows(recovered)
      assertEquals(77L, events.first { it.optString("event") == "process.session_start" }
        .getJSONObject("fields").getLong("versionCode"))
      assertTrue(events.any { it.optString("event") == "test.after_recovery" })
    } finally { writer.close() }
  }

  @Test fun saturatedQueueReportsDroppedEventsWithoutBlockingAlarmCaller() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val drained = CountDownLatch(1)
    val writer = AlarmLogEngine(directory = { directory() }, wallMs = { 1000L }, elapsedMs = { 100L }, queueCapacity = 2)
    try {
      writer.record("test.blocked", contextFields = { entered.countDown(); release.await(5, TimeUnit.SECONDS); emptyMap() })
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      writer.record("test.queued_one")
      writer.record("test.queued_two", contextFields = { drained.countDown(); emptyMap() })
      repeat(5) { writer.record("test.dropped") }
      val rejected = snapshot(writer)
      assertEquals("snapshot_queue_full", rejected.status.snapshotFailure)
      assertTrue(rejected.status.droppedRecords >= 5)
      assertEquals(0, rejected.jsonl.size)
      release.countDown()
      assertTrue(drained.await(5, TimeUnit.SECONDS))
      val exported = snapshot(writer)
      assertNull(exported.status.snapshotFailure)
      assertTrue(exported.status.partial)
      assertTrue(rows(exported).any { it.optString("event") == "logger.health" })
    } finally { release.countDown(); writer.close() }
  }

  @Test fun fatalHandlerAlwaysForwardsOriginalExceptionEvenWhenLoggingFails() {
    val expected = IllegalStateException("fatal")
    val seen = AtomicReference<Throwable>()
    var receivedTimeout = 0L
    val handler = AlarmFatalHandler(
      original = Thread.UncaughtExceptionHandler { _, error -> seen.set(error) },
      report = { _, _ -> throw IOException("logger failed") },
      flush = { timeout -> receivedTimeout = timeout; throw IOException("flush failed") },
    )
    handler.uncaughtException(Thread.currentThread(), expected)
    assertSame(expected, seen.get())
    assertEquals(150L, receivedTimeout)
  }
}
