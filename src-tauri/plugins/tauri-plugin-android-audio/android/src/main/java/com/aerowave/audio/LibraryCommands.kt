package com.aerowave.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResult
import app.tauri.annotation.InvokeArg
import app.tauri.plugin.Invoke
import java.util.concurrent.Executors

@InvokeArg
class FolderArgs {
  lateinit var path: String
}

@InvokeArg
class RandomTrackArgs {
  lateinit var path: String
  var exclude: String? = null
}

object LibraryCommands {
  private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "aerowave-document-library").apply { isDaemon = true }
  }
  private val main = Handler(Looper.getMainLooper())

  fun folderPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
  }

  fun handleFolderPickerResult(context: Context, invoke: Invoke, result: ActivityResult) {
    if (result.resultCode == Activity.RESULT_CANCELED) {
      invoke.resolve()
      return
    }
    if (result.resultCode != Activity.RESULT_OK) {
      invoke.reject("Android could not open the folder picker. Try choosing the folder again.")
      return
    }

    val data = result.data
    val uri = data?.data
    if (uri == null) {
      invoke.reject("Android returned no music folder. Try choosing it again.")
      return
    }
    if (!uri.scheme.equals("content", ignoreCase = true) || !DocumentsContract.isTreeUri(uri)) {
      invoke.reject("Android returned an invalid music folder. Choose a different folder.")
      return
    }
    if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
      invoke.reject("Android did not grant read access to that folder. Choose a different folder.")
      return
    }

    try {
      // Persist read access only. Aerowave never asks to write, rename, or
      // delete anything in a music folder.
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    } catch (error: Exception) {
      invoke.reject(
        "Android could not keep read access to that folder. Choose a folder stored on this phone.",
        error,
      )
      return
    }
    runAsync(invoke) { DocumentLibrary.folderInfo(context, uri.toString()) }
  }

  fun folderInfo(context: Context, invoke: Invoke) {
    val args = try {
      invoke.parseArgs(FolderArgs::class.java)
    } catch (error: Exception) {
      invoke.reject("A music folder path is required.", error)
      return
    }
    runAsync(invoke) { DocumentLibrary.folderInfo(context, args.path) }
  }

  fun randomTrack(context: Context, invoke: Invoke) {
    val args = try {
      invoke.parseArgs(RandomTrackArgs::class.java)
    } catch (error: Exception) {
      invoke.reject("A music folder path is required.", error)
      return
    }
    runAsync(invoke) { DocumentLibrary.randomTrack(context, args.path, args.exclude) }
  }

  private fun runAsync(invoke: Invoke, work: () -> Any) {
    worker.execute {
      try {
        val result = work()
        main.post { invoke.resolveObject(result) }
      } catch (error: Exception) {
        val message = error.message ?: "Android could not read that music folder."
        main.post { invoke.reject(message, error) }
      }
    }
  }
}
