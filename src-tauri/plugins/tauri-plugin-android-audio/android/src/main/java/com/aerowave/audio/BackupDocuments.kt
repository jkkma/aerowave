package com.aerowave.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResult
import app.tauri.plugin.Invoke
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal object BackupDocuments {
  const val MAX_BYTES = 2 * 1024 * 1024
  private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "aerowave-backup-documents").apply { isDaemon = true }
  }
  private val main by lazy { Handler(Looper.getMainLooper()) }

  fun openIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "application/json"
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  fun createIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "application/json"
    putExtra(Intent.EXTRA_TITLE, "Aerowave-backup.json")
    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
  }

  fun readResult(context: Context, invoke: Invoke, result: ActivityResult) {
    val uri = selectedUri(invoke, result, Intent.FLAG_GRANT_READ_URI_PERMISSION) ?: return
    worker.execute {
      try {
        val content = context.contentResolver.openInputStream(uri)?.use(::readUtf8)
          ?: throw IllegalStateException("Android could not open the selected backup")
        main.post { invoke.resolveObject(content) }
      } catch (error: Exception) {
        main.post {
          invoke.reject(error.message ?: "Android could not read the selected backup", error)
        }
      }
    }
  }

  fun writeResult(context: Context, invoke: Invoke, result: ActivityResult, content: String) {
    val uri = selectedUri(invoke, result, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ?: return
    worker.execute {
      try {
        val bytes = encodeUtf8(content)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
          ?: throw IllegalStateException("Android could not write the selected backup")
        val name = runCatching { displayName(context, uri) }.getOrNull() ?: uri.toString()
        main.post { invoke.resolveObject(name) }
      } catch (error: Exception) {
        main.post {
          invoke.reject(error.message ?: "Android could not save the backup", error)
        }
      }
    }
  }

  private fun selectedUri(invoke: Invoke, result: ActivityResult, grant: Int): Uri? {
    if (result.resultCode == Activity.RESULT_CANCELED) {
      invoke.resolve()
      return null
    }
    if (result.resultCode != Activity.RESULT_OK) {
      invoke.reject("Android could not open the document picker")
      return null
    }
    val data = result.data
    val uri = data?.data
    if (uri == null || uri.scheme != "content" || data.flags and grant == 0) {
      invoke.reject("Android did not grant access to the selected document")
      return null
    }
    return uri
  }

  private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.takeIf(String::isNotBlank)
        else null
      }

  internal fun readUtf8(input: java.io.InputStream): String {
    val buffer = ByteArray(MAX_BYTES + 1)
    var count = 0
    while (count < buffer.size) {
      val read = input.read(buffer, count, buffer.size - count)
      if (read < 0) break
      if (read == 0) continue
      count += read
    }
    require(count <= MAX_BYTES) { "Backup exceeds the 2 MiB limit" }
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(buffer, 0, count)).toString()
  }

  internal fun encodeUtf8(content: String): ByteArray {
    require(content.length <= MAX_BYTES) { "Backup exceeds the 2 MiB limit" }
    val encoder = StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val encoded = encoder.encode(CharBuffer.wrap(content))
    require(encoded.remaining() <= MAX_BYTES) { "Backup exceeds the 2 MiB limit" }
    return ByteArray(encoded.remaining()).also { encoded.get(it) }
  }
}
