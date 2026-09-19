package com.aerowave.audio

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Reads only persisted Storage Access Framework trees. No filesystem path is
 * accepted, and no document is opened until its provider proves it belongs to
 * a currently granted tree.
 */
object DocumentLibrary {
  const val MAX_DEPTH = 8
  const val MAX_FILES = 50_000
  const val MAX_ENTRIES = 200_000
  const val MAX_SCAN_DURATION_MS = 8_000L

  private val limits = ScanLimits(
    maxDepth = MAX_DEPTH,
    maxFiles = MAX_FILES,
    maxEntries = MAX_ENTRIES,
    maxDurationMs = MAX_SCAN_DURATION_MS,
  )

  @JvmStatic
  fun folderInfo(context: Context, path: String): FolderInfo {
    val treeUri = requireGrantedTree(context, path)
    val scan = scan(context, treeUri)
    return FolderInfo(
      path = treeUri.toString(),
      name = scan.folderName,
      count = scan.tracks.size,
    )
  }

  @JvmStatic
  fun randomTrack(context: Context, path: String, exclude: String? = null): TrackPick {
    val treeUri = requireGrantedTree(context, path)
    val scan = scan(context, treeUri)
    if (scan.tracks.isEmpty()) {
      throw LibraryAccessException(
        "No supported audio files were found in that folder. Choose a folder containing MP3, M4A, AAC, FLAC, Ogg or Opus, WAV, or WebM audio.",
      )
    }
    val chosen = chooseTrack(scan.tracks, exclude) { Random.nextInt(it) }
    return TrackPick(chosen.uri, chosen.name, scan.tracks.size)
  }

  /**
   * Confirms that a content URI is still readable, is within a persisted tree,
   * and describes a format the native player accepts.
   */
  @JvmStatic
  fun validatePlayable(context: Context, uri: Uri): Boolean {
    requireContentUri(uri, "track")
    if (!DocumentsContract.isDocumentUri(context, uri) || !DocumentsContract.isTreeUri(uri)) {
      throw LibraryAccessException(
        "That track is not from the selected Android document folder. Choose the music folder again in Setup.",
      )
    }
    val resolver = context.contentResolver
    val authority = uri.authority ?: throw LibraryAccessException(
      "That track has no Android document provider. Choose the music folder again in Setup.",
    )
    val treeUri = try {
      DocumentsContract.buildTreeDocumentUri(
        authority,
        DocumentsContract.getTreeDocumentId(uri),
      )
    } catch (error: Exception) {
      throw LibraryAccessException(
        "That track has an invalid Android document address. Choose the music folder again in Setup.",
        error,
      )
    }
    val exactGrant = resolver.persistedUriPermissions.any {
      it.isReadPermission && it.uri == treeUri
    }
    if (!exactGrant) {
      throw LibraryAccessException(
        "Aerowave no longer has access to that exact music folder. Choose it again in Setup.",
      )
    }

    val deadline = ScanDeadline(MAX_SCAN_DURATION_MS, SystemClock::elapsedRealtime)
    val reader = DeadlineContentReader(context, deadline)
    val root = DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )
    val insideGrantedTree = try {
      deadline.check()
      (uri == root || DocumentsContract.isChildDocument(resolver, root, uri)).also {
        deadline.check()
      }
    } catch (error: LibraryAccessException) {
      throw error
    } catch (error: Exception) {
      throw LibraryAccessException(
        "Android could not verify that track in the chosen music folder.",
        error,
      )
    }
    if (!insideGrantedTree) {
      throw LibraryAccessException(
        "That track is outside the chosen music folder. Choose the folder again in Setup.",
      )
    }

    val document = try {
      queryDocument(reader, uri)
    } catch (error: SecurityException) {
      throw LibraryAccessException(
        "Aerowave can no longer read that track. Choose the music folder again in Setup.",
        error,
      )
    } ?: return false
    if (document.isDirectory || document.isVirtual ||
      !SupportedAudio.accepts(document.name, document.mimeType)
    ) {
      return false
    }
    return canOpenForRead(reader, uri)
  }

  private fun scan(context: Context, treeUri: Uri): LibraryScan = try {
    val deadline = ScanDeadline(MAX_SCAN_DURATION_MS, SystemClock::elapsedRealtime)
    DocumentTreeScanner.scan(
      AndroidDocumentTree(context, treeUri, deadline),
      limits,
      deadline,
    )
  } catch (error: LibraryAccessException) {
    throw error
  } catch (error: SecurityException) {
    throw LibraryAccessException(
      "Aerowave can no longer read that music folder. Choose the folder again in Setup.",
      error,
    )
  } catch (error: Exception) {
    throw LibraryAccessException(
      "Android could not read that music folder. Choose it again or select a folder stored on this phone.",
      error,
    )
  }

  private fun requireGrantedTree(context: Context, path: String): Uri {
    val uri = try {
      Uri.parse(path)
    } catch (error: Exception) {
      throw LibraryAccessException(
        "That music folder address is invalid. Choose the folder again in Setup.",
        error,
      )
    }
    requireContentUri(uri, "folder")
    if (!DocumentsContract.isTreeUri(uri)) {
      throw LibraryAccessException(
        "That folder is not an Android document folder. Choose it again in Setup.",
      )
    }
    val granted = context.contentResolver.persistedUriPermissions.any {
      it.isReadPermission && it.uri == uri
    }
    if (!granted) {
      throw LibraryAccessException(
        "Aerowave no longer has access to that music folder. Choose the folder again in Setup.",
      )
    }
    return uri
  }

  private fun requireContentUri(uri: Uri, kind: String) {
    if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
      throw LibraryAccessException(
        "That $kind is not available through Android document access. Choose the folder again in Setup.",
      )
    }
  }

}

private class AndroidDocumentTree(
  context: Context,
  private val treeUri: Uri,
  private val deadline: ScanDeadline,
) : DocumentTree {
  private val resolver = context.contentResolver
  private val reader = DeadlineContentReader(context, deadline)
  override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
  private val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
  override val rootName: String = queryRootName()

  override fun forEachChild(parentId: String, accept: (LibraryDocument) -> Unit) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_FLAGS,
    )
    reader.query(childrenUri, projection) { cursor ->
      while (cursor.moveToNext()) accept(documentFromCursor(cursor))
    } ?: throw LibraryAccessException("Android did not return the contents of that music folder.")
  }

  override fun isInsideRoot(document: LibraryDocument): Boolean {
    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, document.id)
    return try {
      deadline.check()
      DocumentsContract.isChildDocument(resolver, rootUri, childUri).also { deadline.check() }
    } catch (error: LibraryAccessException) {
      throw error
    } catch (error: Exception) {
      throw LibraryAccessException(
        "Android could not verify an item in that music folder. Choose a different folder.",
        error,
      )
    }
  }

  override fun canRead(document: LibraryDocument): Boolean =
    canOpenForRead(reader, documentUri(document.id))

  override fun uri(document: LibraryDocument): String = documentUri(document.id).toString()

  private fun documentUri(documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

  private fun queryRootName(): String {
    val root = queryDocument(reader, rootUri)
      ?: throw LibraryAccessException("That music folder no longer exists. Choose it again in Setup.")
    if (!root.isDirectory) {
      throw LibraryAccessException("The saved music location is no longer a folder. Choose it again in Setup.")
    }
    return root.name.ifBlank { rootId.substringAfterLast(':').ifBlank { "Music folder" } }
  }
}

private fun documentFromCursor(cursor: Cursor): LibraryDocument {
  val id = cursor.getString(
    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
  )
  val name = cursor.getString(
    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
  ).orEmpty()
  val mime = cursor.getString(
    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
  )
  val flags = cursor.getInt(
    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS),
  )
  return LibraryDocument(
    id = id,
    name = name,
    mimeType = mime,
    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
    isVirtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
  )
}

private fun canOpenForRead(reader: DeadlineContentReader, uri: Uri): Boolean = try {
  reader.openFileDescriptor(uri)?.use { descriptor ->
    descriptor.statSize != 0L
  } ?: false
} catch (_: FileNotFoundException) {
  false
} catch (_: SecurityException) {
  false
}

private fun queryDocument(reader: DeadlineContentReader, uri: Uri): LibraryDocument? {
  val projection = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_FLAGS,
  )
  return try {
    reader.query(uri, projection, read@ { cursor ->
      if (!cursor.moveToFirst()) return@read null
      documentFromCursor(cursor)
    })
  } catch (_: FileNotFoundException) {
    null
  }
}

private class DeadlineContentReader(
  context: Context,
  private val deadline: ScanDeadline,
) {
  private val resolver = context.contentResolver

  fun <T> query(uri: Uri, projection: Array<String>, read: (Cursor) -> T): T? =
    withCancellation { cancellation ->
      resolver.query(uri, projection, null, null, null, cancellation)?.use(read)
    }

  fun openFileDescriptor(uri: Uri) = withCancellation { cancellation ->
    resolver.openFileDescriptor(uri, "r", cancellation)
  }

  private fun <T> withCancellation(block: (CancellationSignal) -> T): T {
    val cancellation = CancellationSignal()
    val timer = cancellationScheduler.schedule(
      cancellation::cancel,
      deadline.remainingMs(),
      TimeUnit.MILLISECONDS,
    )
    return try {
      block(cancellation).also { deadline.check() }
    } catch (error: OperationCanceledException) {
      deadline.timeout(error)
    } finally {
      timer.cancel(false)
    }
  }

  companion object {
    private val cancellationScheduler = Executors.newSingleThreadScheduledExecutor { task ->
      Thread(task, "aerowave-document-timeout").apply { isDaemon = true }
    }
  }
}
