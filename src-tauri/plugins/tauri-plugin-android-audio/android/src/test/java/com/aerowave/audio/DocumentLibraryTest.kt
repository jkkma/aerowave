package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentLibraryTest {
  private class FakeTree(
    override val rootName: String = "Wake music",
    private val children: Map<String, List<LibraryDocument>>,
    private val outside: Set<String> = emptySet(),
    private val unreadable: Set<String> = emptySet(),
  ) : DocumentTree {
    override val rootId = "root"

    override fun forEachChild(parentId: String, accept: (LibraryDocument) -> Unit) {
      children[parentId].orEmpty().forEach(accept)
    }

    override fun isInsideRoot(document: LibraryDocument): Boolean = document.id !in outside

    override fun canRead(document: LibraryDocument): Boolean = document.id !in unreadable

    override fun uri(document: LibraryDocument): String = "content://music/${document.id}"
  }

  private val generous = ScanLimits(
    maxDepth = 8,
    maxFiles = 50_000,
    maxEntries = 200_000,
    maxDurationMs = 8_000,
  )

  @Test
  fun recursiveScanKeepsOnlyReadableSupportedNonVirtualAudio() {
    val album = LibraryDocument("album", "Album", "vnd.android.document/directory", true)
    val tree = FakeTree(
      children = mapOf(
        "root" to listOf(
          album,
          LibraryDocument("top", "Wake.MP3", "audio/mpeg", false),
          LibraryDocument("cover", "cover.jpg", "image/jpeg", false),
          LibraryDocument("spoof", "not-a-song.mp3", "text/plain", false),
        ),
        "album" to listOf(
          LibraryDocument("opus", "morning.opus", "audio/opus", false),
          LibraryDocument("generic", "rain.FLAC", "application/octet-stream", false),
          LibraryDocument("virtual", "cloud.ogg", "audio/ogg", false, isVirtual = true),
          LibraryDocument("gone", "gone.wav", "audio/wav", false),
        ),
      ),
      unreadable = setOf("gone"),
    )

    val result = DocumentTreeScanner.scan(tree, generous) { 1_000 }

    assertEquals("Wake music", result.folderName)
    assertEquals(
      listOf("Wake.MP3", "morning.opus", "rain.FLAC"),
      result.tracks.map { it.name },
    )
  }

  @Test
  fun supportedFormatsRequireKnownMimeOrGenericMimeWithKnownExtension() {
    assertTrue(SupportedAudio.accepts("extensionless", "audio/flac"))
    assertTrue(SupportedAudio.accepts("track.m4a", null))
    assertTrue(SupportedAudio.accepts("track.WEBM", "application/octet-stream"))
    assertFalse(SupportedAudio.accepts("video.mp4", "video/mp4"))
    assertFalse(SupportedAudio.accepts("track.wma", "audio/x-ms-wma"))
    assertFalse(SupportedAudio.accepts("fake.mp3", "text/plain"))
  }

  @Test
  fun unsafeProviderChildFailsInsteadOfEscapingTheGrantedTree() {
    val tree = FakeTree(
      children = mapOf(
        "root" to listOf(LibraryDocument("elsewhere", "secret.mp3", "audio/mpeg", false)),
      ),
      outside = setOf("elsewhere"),
    )

    val error = expectLibraryFailure {
      DocumentTreeScanner.scan(tree, generous) { 1_000 }
    }

    assertTrue(error.message.orEmpty().contains("outside the chosen music folder"))
  }

  @Test
  fun everyBoundFailsInsteadOfReturningAFalseCompleteCount() {
    val deepTree = FakeTree(
      children = mapOf(
        "root" to listOf(LibraryDocument("d1", "d1", "dir", true)),
        "d1" to listOf(LibraryDocument("d2", "d2", "dir", true)),
      ),
    )
    val depthError = expectLibraryFailure {
      DocumentTreeScanner.scan(deepTree, generous.copy(maxDepth = 1)) { 1_000 }
    }
    assertTrue(depthError.message.orEmpty().contains("nested more than 1 levels"))

    val entries = FakeTree(
      children = mapOf(
        "root" to listOf(
          LibraryDocument("one", "one.txt", "text/plain", false),
          LibraryDocument("two", "two.txt", "text/plain", false),
        ),
      ),
    )
    val entryError = expectLibraryFailure {
      DocumentTreeScanner.scan(entries, generous.copy(maxEntries = 1)) { 1_000 }
    }
    assertTrue(entryError.message.orEmpty().contains("too many items"))

    val files = FakeTree(
      children = mapOf(
        "root" to listOf(
          LibraryDocument("one", "one.mp3", "audio/mpeg", false),
          LibraryDocument("two", "two.mp3", "audio/mpeg", false),
        ),
      ),
    )
    val fileError = expectLibraryFailure {
      DocumentTreeScanner.scan(files, generous.copy(maxFiles = 1)) { 1_000 }
    }
    assertTrue(fileError.message.orEmpty().contains("more than 1 supported audio files"))
  }

  @Test
  fun elapsedLimitInterruptsTraversal() {
    var now = 0L
    val tree = FakeTree(
      children = mapOf(
        "root" to listOf(LibraryDocument("one", "one.mp3", "audio/mpeg", false)),
      ),
    )

    val error = expectLibraryFailure {
      DocumentTreeScanner.scan(tree, generous.copy(maxDurationMs = 5)) { now.also { now += 3 } }
    }

    assertTrue(error.message.orEmpty().contains("took too long"))
  }

  @Test
  fun exclusionAvoidsTheCurrentTrackButOneTrackFoldersStillPlay() {
    val first = LibraryTrack("content://music/first", "first.mp3")
    val second = LibraryTrack("content://music/second", "second.mp3")

    assertEquals(second, chooseTrack(listOf(first, second), first.uri) { 0 })
    assertEquals(first, chooseTrack(listOf(first), first.uri) { 0 })
  }

  @Test
  fun providerAliasesDoNotInflateThePlayableCount() {
    val same = LibraryDocument("same", "same.mp3", "audio/mpeg", false)
    val tree = FakeTree(children = mapOf("root" to listOf(same, same)))

    val result = DocumentTreeScanner.scan(tree, generous) { 1_000 }

    assertEquals(listOf("same.mp3"), result.tracks.map { it.name })
  }

  private fun expectLibraryFailure(block: () -> Unit): LibraryAccessException {
    try {
      block()
      fail("Expected LibraryAccessException")
    } catch (error: LibraryAccessException) {
      return error
    }
    throw AssertionError("unreachable")
  }
}
