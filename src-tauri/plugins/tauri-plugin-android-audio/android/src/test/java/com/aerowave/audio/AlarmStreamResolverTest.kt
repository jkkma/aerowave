package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmStreamResolverTest {
  @Test
  fun detectsWrappersFromExtensionOrContentType() {
    assertEquals(AlarmPlaylistKind.PLS, AlarmPlaylistParser.kind("https://radio.test/list.PLS?x=1", null))
    assertEquals(AlarmPlaylistKind.PLS, AlarmPlaylistParser.kind("https://radio.test/list", "audio/x-scpls"))
    assertEquals(AlarmPlaylistKind.M3U, AlarmPlaylistParser.kind("https://radio.test/list.m3u8", null))
    assertEquals(AlarmPlaylistKind.M3U, AlarmPlaylistParser.kind("https://radio.test/list", "application/vnd.apple.mpegurl"))
    assertEquals(AlarmPlaylistKind.ASX, AlarmPlaylistParser.kind("https://radio.test/list.asx", null))
    assertEquals(AlarmPlaylistKind.ASX, AlarmPlaylistParser.kind("https://radio.test/list", "video/x-ms-asf"))
    assertEquals(AlarmPlaylistKind.NONE, AlarmPlaylistParser.kind("https://radio.test/live.mp3", "audio/mpeg"))
  }

  @Test
  fun parsesPlsEntriesInProviderOrder() {
    val body = """
      [playlist]
      NumberOfEntries=2
      File1=https://one.test/live
      Title1=One
      File2=relative/two.mp3
    """.trimIndent()

    assertEquals(
      listOf("https://one.test/live", "relative/two.mp3"),
      AlarmPlaylistParser.entries(AlarmPlaylistKind.PLS, body),
    )
  }

  @Test
  fun parsesM3uEntriesAndLeavesRelativeResolutionToTheGuardedFetcher() {
    val body = """
      #EXTM3U
      #EXTINF:-1,One
      https://one.test/live
      ../relative/two.aac
      ; ignored comment
    """.trimIndent()

    assertEquals(
      listOf("https://one.test/live", "../relative/two.aac"),
      AlarmPlaylistParser.entries(AlarmPlaylistKind.M3U, body),
    )
  }

  @Test
  fun parsesXmlAndIniAsxReferencesAndDecodesQuerySeparators() {
    val body = """
      <ASX version="3"><ENTRY><REF HREF="https://one.test/live?a=1&amp;b=2" /></ENTRY></ASX>
      Ref2=../relative/two
    """.trimIndent()

    assertEquals(
      listOf("https://one.test/live?a=1&b=2", "../relative/two"),
      AlarmPlaylistParser.entries(AlarmPlaylistKind.ASX, body),
    )
  }

  @Test
  fun recognizesOnlyExtendedM3uAsHls() {
    assertTrue(AlarmPlaylistParser.isHls("#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts"))
    assertFalse(AlarmPlaylistParser.isHls("#EXTM3U\n#EXTINF:-1,Radio\nhttps://radio.test/live"))
    assertFalse(AlarmPlaylistParser.isHls("#EXT-X-VERSION:3\nsegment.ts"))
  }

  @Test
  fun rejectsHtmlContentTypesBeforeReadingTheirBodies() {
    assertTrue(AlarmPlaylistParser.isHtml("text/html; charset=utf-8"))
    assertTrue(AlarmPlaylistParser.isHtml("application/xhtml+xml"))
    assertFalse(AlarmPlaylistParser.isHtml("audio/mpeg"))
  }

  @Test
  fun candidateListIsDeduplicatedAndBounded() {
    val body = (listOf("https://radio.test/0", "https://radio.test/0") +
      (1..80).map { "https://radio.test/$it" }).joinToString("\n")

    val entries = AlarmPlaylistParser.entries(AlarmPlaylistKind.M3U, body)

    assertEquals(64, entries.size)
    assertEquals("https://radio.test/0", entries.first())
    assertEquals(64, entries.distinct().size)
  }
}
