package com.aerowave.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import app.tauri.plugin.JSObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class SavedAlarmLogs(
  val name: String,
  val bytes: Int,
  val partial: Boolean,
  val droppedRecords: Long,
  val writeFailures: Long,
  val historyTruncated: Boolean,
  val recoveredIncompleteData: Boolean,
) {
  // Explicit keys survive R8 and keep the log contents inside the native layer.
  fun toJsObject(): JSObject = JSObject().apply {
    put("name", name)
    put("mimeType", AlarmLogDocuments.MIME_TYPE)
    put("bytes", bytes)
    put("partial", partial)
    put("droppedRecords", droppedRecords)
    put("writeFailures", writeFailures)
    put("historyTruncated", historyTruncated)
    put("recoveredIncompleteData", recoveredIncompleteData)
  }
}

internal object AlarmLogDocuments {
  const val MIME_TYPE = "application/x-ndjson"
  private val writer = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue<Runnable>(),
    { task -> Thread(task, "aerowave-alarm-log-export").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
  )
  private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, "aerowave-alarm-log-export-deadline").apply { isDaemon = true }
  }.apply { removeOnCancelPolicy = true }
  private val exports = AlarmLogExportCoordinator(writer, { task, delayMs ->
    val scheduled = deadlines.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    val cancel: () -> Unit = { scheduled.cancel(false); Unit }
    cancel
  })

  fun isBusy(): Boolean = exports.isBusy()

  fun reserve(
    context: Context,
    callback: (Result<SavedAlarmLogs?>) -> Unit,
  ): AlarmLogExportCoordinator.Request? {
    val application = context.applicationContext ?: context
    return exports.reserve { result ->
      result.fold(
        onSuccess = { saved ->
          if (saved != null) AlarmEventLog.record(application, "logs.export_saved", fields = mapOf(
            "bytes" to saved.bytes, "partial" to saved.partial,
          ))
        },
        onFailure = { error -> AlarmEventLog.record(application,
          if (error is TimeoutException) "logs.export_timed_out" else "logs.export_failed", error = error) },
      )
      callback(result)
    }
  }

  fun createIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = MIME_TYPE
    putExtra(Intent.EXTRA_TITLE, defaultName())
    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
  }

  private fun defaultName(): String =
    "Aerowave-alarm-logs-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jsonl"

  internal fun selectedUri(result: ActivityResult): Uri? {
    if (result.resultCode == Activity.RESULT_CANCELED) return null
    require(result.resultCode == Activity.RESULT_OK) { "Android could not open the document picker" }
    val data = result.data
    val uri = data?.data
    require(data != null && uri != null && uri.scheme == "content" &&
      data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
      "Android did not grant write access to the selected document"
    }
    return uri
  }

  fun writeResult(
    context: Context,
    result: ActivityResult,
    defaultName: String,
    request: AlarmLogExportCoordinator.Request,
  ) {
    val uri = try {
      selectedUri(result)
    } catch (error: Exception) {
      request.finishWithoutWriting(Result.failure(error))
      return
    }
    if (uri == null) {
      request.finishWithoutWriting(Result.success(null))
      return
    }
    val application = context.applicationContext ?: context
    request.write(
      capture = { callback -> AlarmEventLog.snapshot(application, callback) },
      save = { snapshot -> writeSnapshot(application, uri, snapshot, defaultName) },
    )
  }

  internal fun writeSnapshot(
    context: Context,
    uri: Uri,
    snapshot: AlarmEventLog.Snapshot,
    defaultName: String,
  ): SavedAlarmLogs {
    require(snapshot.status.snapshotFailure == null && snapshot.jsonl.isNotEmpty()) {
      "Alarm logs could not be read. Try exporting again."
    }
    context.contentResolver.openOutputStream(uri, "wt")?.use {
      it.write(snapshot.jsonl)
      it.flush()
    } ?: throw IllegalStateException("Android could not write the selected alarm log file")
    return SavedAlarmLogs(
      defaultName, snapshot.jsonl.size,
      snapshot.status.partial || snapshot.status.droppedRecords > 0 || snapshot.status.writeFailures > 0 ||
        snapshot.status.recoveredIncompleteData,
      snapshot.status.droppedRecords, snapshot.status.writeFailures,
      snapshot.status.historyTruncated, snapshot.status.recoveredIncompleteData,
    )
  }
}
