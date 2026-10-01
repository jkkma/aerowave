package com.aerowave.audio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmLogDocumentsTest {
  private val destination = Uri.parse("content://documents/alarm-logs")

  private fun snapshot(
    bytes: ByteArray = "{\"event\":\"test\"}\n".toByteArray(),
    dropped: Long = 0,
    failed: Long = 0,
    failure: String? = null,
    rotated: Boolean = false,
    recovered: Boolean = false,
  ) = AlarmEventLog.Snapshot(bytes, AlarmEventLog.Status(
    sessionId = "test-session", droppedRecords = dropped, writeFailures = failed,
    retainedBytes = bytes.size, queueDepth = 0, partial = false,
    snapshotFailure = failure, historyTruncated = rotated, recoveredIncompleteData = recovered,
  ))

  @Test fun cancellationAndRevokedWriteAccessCannotStartASave() {
    assertNull(AlarmLogDocuments.selectedUri(ActivityResult(Activity.RESULT_CANCELED, null)))
    val granted = Intent().setData(destination).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    assertEquals(destination, AlarmLogDocuments.selectedUri(ActivityResult(Activity.RESULT_OK, granted)))
    val readOnly = Intent().setData(destination).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    assertThrows(IllegalArgumentException::class.java) {
      AlarmLogDocuments.selectedUri(ActivityResult(Activity.RESULT_OK, readOnly))
    }
    val file = Intent().setData(Uri.parse("file:///ungranted/log.jsonl"))
      .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    assertThrows(IllegalArgumentException::class.java) {
      AlarmLogDocuments.selectedUri(ActivityResult(Activity.RESULT_OK, file))
    }
  }

  @Test fun exportWritesTheNativeSnapshotAndReturnsOnlyMetadataWithLossCounters() {
    val context = RuntimeEnvironment.getApplication()
    val output = ByteArrayOutputStream()
    Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, output)
    val source = snapshot(dropped = 2, failed = 3)
    val saved = AlarmLogDocuments.writeSnapshot(context, destination, source, "alarm-logs.jsonl")
    assertArrayEquals(source.jsonl, output.toByteArray())
    assertArrayEquals(source.jsonl, "{\"event\":\"test\"}\n".toByteArray())
    assertTrue(saved.partial)
    assertEquals(2L, saved.droppedRecords)
    assertEquals(3L, saved.writeFailures)
    val response = saved.toJsObject()
    assertEquals("alarm-logs.jsonl", response.getString("name"))
    assertEquals("application/x-ndjson", response.getString("mimeType"))
    assertEquals(source.jsonl.size, response.getInt("bytes"))
    assertEquals(setOf(
      "name", "mimeType", "bytes", "partial", "droppedRecords", "writeFailures",
      "historyTruncated", "recoveredIncompleteData",
    ), response.keys().asSequence().toSet())
  }

  @Test fun expectedRotationIsNeutralAndRecoveredIncompleteRecordsWarn() {
    val context = RuntimeEnvironment.getApplication()
    Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, ByteArrayOutputStream())
    val retained = AlarmLogDocuments.writeSnapshot(context, destination, snapshot(rotated = true), "logs.jsonl")
    assertTrue(retained.historyTruncated)
    assertFalse(retained.partial)
    val recovered = AlarmLogDocuments.writeSnapshot(context, destination, snapshot(recovered = true), "logs.jsonl")
    assertTrue(recovered.recoveredIncompleteData)
    assertTrue(recovered.partial)
  }

  @Test fun failedOrEmptySnapshotsCannotOverwriteTheSelectedDocument() {
    val context = RuntimeEnvironment.getApplication()
    val output = ByteArrayOutputStream().apply { write("existing".toByteArray()) }
    Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, output)
    for (source in listOf(snapshot(failure = "read_failed"), snapshot(bytes = ByteArray(0)))) {
      assertThrows(IllegalArgumentException::class.java) {
        AlarmLogDocuments.writeSnapshot(context, destination, source, "logs.jsonl")
      }
      assertEquals("existing", output.toString("UTF-8"))
    }
  }

  @Test fun aDocumentProviderWriteFailureIsNotReportedAsSaved() {
    val context = RuntimeEnvironment.getApplication()
    Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, object : OutputStream() {
      override fun write(value: Int) { throw IOException("write failed") }
    })
    assertThrows(IOException::class.java) {
      AlarmLogDocuments.writeSnapshot(context, destination, snapshot(), "logs.jsonl")
    }
  }

  @Test fun aHungProviderTimesOutOnceAndRetainsAdmissionWithoutQueuingMoreExports() {
    val context = RuntimeEnvironment.getApplication()
    val entered = CountDownLatch(1)
    val releaseProvider = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val firstByte = AtomicBoolean(true)
    Shadows.shadowOf(context.contentResolver).registerOutputStream(destination, object : OutputStream() {
      override fun write(value: Int) {
        if (!firstByte.compareAndSet(true, false)) return
        entered.countDown()
        while (true) {
          try { releaseProvider.await(); return }
          catch (_: InterruptedException) { interrupted.countDown() }
        }
      }
    })
    val writer = ThreadPoolExecutor(
      1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>(),
      { task -> Thread(task, "test-alarm-export").apply { isDaemon = true } },
      ThreadPoolExecutor.AbortPolicy(),
    )
    var deadline: Runnable? = null
    val coordinator = AlarmLogExportCoordinator(writer, { task, delay ->
      assertEquals(30_000L, delay)
      deadline = task
      val cancel: () -> Unit = {}
      cancel
    })
    val results = CopyOnWriteArrayList<Result<SavedAlarmLogs?>>()
    val request = coordinator.reserve { results.add(it) }!!
    try {
      request.write(
        capture = { it(snapshot()) },
        save = { AlarmLogDocuments.writeSnapshot(context, destination, it, "logs.jsonl") },
      )
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      deadline!!.run()
      assertEquals(1, results.size)
      assertTrue(results.single().exceptionOrNull() is TimeoutException)
      assertTrue(interrupted.await(2, TimeUnit.SECONDS))
      repeat(20) { assertNull(coordinator.reserve { error("A busy export must not be admitted") }) }
      assertEquals(1, writer.largestPoolSize)
      assertTrue(writer.queue.isEmpty())
      releaseProvider.countDown()
      writer.shutdown()
      assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS))
      assertEquals(1, results.size)
      val next = coordinator.reserve {}!!
      next.finishWithoutWriting(Result.success(null))
    } finally {
      releaseProvider.countDown()
      writer.shutdownNow()
      writer.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  @Test fun aDelayedSnapshotCannotStartAWriteAfterItsDeadlineOrAllowAQueueOfSnapshots() {
    var deadline: Runnable? = null
    var deliverSnapshot: ((AlarmEventLog.Snapshot) -> Unit)? = null
    var wrote = false
    val coordinator = AlarmLogExportCoordinator(Executor { it.run() }, { task, _ ->
      deadline = task
      val cancel: () -> Unit = {}
      cancel
    })
    val results = mutableListOf<Result<SavedAlarmLogs?>>()
    val request = coordinator.reserve { results.add(it) }!!
    request.write(
      capture = { deliverSnapshot = it },
      save = { wrote = true; SavedAlarmLogs("logs.jsonl", 1, false, 0, 0, false, false) },
    )
    deadline!!.run()
    assertEquals(1, results.size)
    assertTrue(results.single().exceptionOrNull() is TimeoutException)
    assertNull(coordinator.reserve {})
    deliverSnapshot!!(snapshot())
    assertFalse(wrote)
    assertEquals(1, results.size)
    coordinator.reserve {}!!.finishWithoutWriting(Result.success(null))
  }

  @Test fun rejectedDocumentResultReleasesProcessWideAdmission() {
    val results = mutableListOf<Result<SavedAlarmLogs?>>()
    val coordinator = AlarmLogExportCoordinator(Executor { it.run() }, { _, _ -> {} })
    val request = coordinator.reserve { results.add(it) }!!
    assertNull(coordinator.reserve {})
    request.finishWithoutWriting(Result.failure(IOException("No write access")))
    assertEquals(1, results.size)
    assertTrue(results.single().exceptionOrNull() is IOException)
    coordinator.reserve {}!!.finishWithoutWriting(Result.success(null))
  }
}
