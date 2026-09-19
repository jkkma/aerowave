package com.aerowave.audio

import java.util.ArrayDeque
import java.util.Locale

data class FolderInfo(
  val path: String,
  val name: String,
  val count: Int,
)

data class TrackPick(
  val path: String,
  val name: String,
  val total: Int,
)

class LibraryAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class LibraryDocument(
  val id: String,
  val name: String,
  val mimeType: String?,
  val isDirectory: Boolean,
  val isVirtual: Boolean = false,
)

internal data class LibraryTrack(
  val uri: String,
  val name: String,
)

internal data class LibraryScan(
  val folderName: String,
  val tracks: List<LibraryTrack>,
)

internal data class ScanLimits(
  val maxDepth: Int,
  val maxFiles: Int,
  val maxEntries: Int,
  val maxDurationMs: Long,
)

internal class ScanDeadline(
  private val maxDurationMs: Long,
  private val nowMs: () -> Long,
) {
  private val startedAt = nowMs()

  init {
    require(maxDurationMs > 0)
  }

  fun check() {
    remainingMs()
  }

  fun remainingMs(): Long {
    val elapsed = (nowMs() - startedAt).coerceAtLeast(0)
    val remaining = maxDurationMs - elapsed
    if (remaining <= 0) timeout()
    return remaining
  }

  fun timeout(cause: Throwable? = null): Nothing = throw LibraryAccessException(
    "Scanning that music folder took too long. Choose a smaller folder or one stored on this phone.",
    cause,
  )
}

/** A narrow seam keeps traversal policy testable without an Android document provider. */
internal interface DocumentTree {
  val rootId: String
  val rootName: String

  fun forEachChild(parentId: String, accept: (LibraryDocument) -> Unit)
  fun isInsideRoot(document: LibraryDocument): Boolean
  fun canRead(document: LibraryDocument): Boolean
  fun uri(document: LibraryDocument): String
}

internal object SupportedAudio {
  private val extensions = setOf(
    "mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav",
    "weba", "webm",
  )

  private val mimeTypes = setOf(
    "audio/mpeg",
    "audio/mp3",
    "audio/x-mp3",
    "audio/mp4",
    "audio/x-m4a",
    "audio/m4a",
    "audio/aac",
    "audio/aacp",
    "audio/x-aac",
    "audio/flac",
    "audio/x-flac",
    "audio/ogg",
    "application/ogg",
    "audio/opus",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
    "audio/vnd.wave",
    "audio/webm",
  )

  private val genericMimeTypes = setOf(
    "",
    "application/octet-stream",
    "application/x-octet-stream",
  )

  fun accepts(name: String, mimeType: String?): Boolean {
    val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (mime in mimeTypes) return true
    if (mime !in genericMimeTypes) return false
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
      .lowercase(Locale.ROOT)
    return extension in extensions
  }
}

internal object DocumentTreeScanner {
  private data class PendingDirectory(val id: String, val depth: Int)

  fun scan(
    tree: DocumentTree,
    limits: ScanLimits,
    nowMs: () -> Long,
  ): LibraryScan = scan(tree, limits, ScanDeadline(limits.maxDurationMs, nowMs))

  fun scan(
    tree: DocumentTree,
    limits: ScanLimits,
    deadline: ScanDeadline,
  ): LibraryScan {
    require(limits.maxDepth >= 0)
    require(limits.maxFiles > 0)
    require(limits.maxEntries > 0)
    require(limits.maxDurationMs > 0)

    val pending = ArrayDeque<PendingDirectory>()
    val visitedDocuments = mutableSetOf(tree.rootId)
    val tracks = mutableListOf<LibraryTrack>()
    var entries = 0
    pending.add(PendingDirectory(tree.rootId, 0))

    while (pending.isNotEmpty()) {
      deadline.check()
      val directory = pending.removeFirst()
      tree.forEachChild(directory.id) { child ->
        deadline.check()
        entries += 1
        if (entries > limits.maxEntries) {
          throw LibraryAccessException(
            "That music folder contains too many items to scan safely. Choose a smaller folder.",
          )
        }
        if (!tree.isInsideRoot(child)) {
          throw LibraryAccessException(
            "Android returned an item outside the chosen music folder. Choose a different folder.",
          )
        }
        if (!visitedDocuments.add(child.id)) return@forEachChild

        val childDepth = directory.depth + 1
        if (childDepth > limits.maxDepth) {
          throw LibraryAccessException(
            "That music folder is nested more than ${limits.maxDepth} levels deep. Choose a smaller folder.",
          )
        }
        if (child.isDirectory) {
          pending.add(PendingDirectory(child.id, childDepth))
          return@forEachChild
        }
        if (child.isVirtual || !SupportedAudio.accepts(child.name, child.mimeType)) {
          return@forEachChild
        }
        if (!tree.canRead(child)) return@forEachChild

        tracks.add(LibraryTrack(tree.uri(child), child.name))
        if (tracks.size > limits.maxFiles) {
          throw LibraryAccessException(
            "That music folder contains more than ${limits.maxFiles} supported audio files. Choose a smaller folder.",
          )
        }
      }
    }

    deadline.check()
    return LibraryScan(tree.rootName, tracks)
  }
}

internal fun chooseTrack(
  tracks: List<LibraryTrack>,
  exclude: String?,
  nextIndex: (Int) -> Int,
): LibraryTrack {
  require(tracks.isNotEmpty())
  val alternatives = if (exclude == null) tracks else tracks.filter { it.uri != exclude }
  val candidates = alternatives.ifEmpty { tracks }
  return candidates[nextIndex(candidates.size)]
}
