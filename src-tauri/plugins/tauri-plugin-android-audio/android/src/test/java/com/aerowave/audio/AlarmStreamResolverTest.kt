package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source

class AlarmStreamResolverTest {
  private data class Reply(val body: String, val code: Int = 200, val mime: String = "audio/mpeg", val location: String? = null, val brokenRead: Boolean = false)

  private fun fixture(replies: Map<String, Reply>, visited: MutableList<String>, before: () -> Unit = {}): OkHttpClient =
    OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      val path = request.url.encodedPath
      visited += path
      before()
      val reply = replies[path] ?: error("Unexpected fixture request: $path")
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(reply.code).message("Fixture").header("Content-Type", reply.mime)
        .apply { reply.location?.let { header("Location", it) } }
        .body(if (reply.brokenRead) object : ResponseBody() {
          override fun contentType() = null
          override fun contentLength() = -1L
          override fun source() = object : InputStream() {
            override fun read(): Int = throw IOException("Fixture body disconnected")
          }.source().buffer()
        } else reply.body.toResponseBody()).build()
    }.build()

  @Test fun exhaustedNestedPlaylistStillTriesItsWorkingSibling() {
    val visited = mutableListOf<String>()
    val client = fixture(mapOf(
      "/root.pls" to Reply("[playlist]\nFile1=first.m3u\nFile2=working.mp3", mime = "audio/x-scpls"),
      "/first.m3u" to Reply("#EXTM3U\ndead.mp3", mime = "audio/x-mpegurl"),
      "/dead.mp3" to Reply("Unavailable", code = 404),
      "/working.mp3" to Reply("audio"),
    ), visited)
    assertEquals("https://radio.test/working.mp3",
      AlarmStreamResolver.resolveWithClient("https://radio.test/root.pls", client).url)
    assertEquals(listOf("/root.pls", "/first.m3u", "/dead.mp3", "/working.mp3"), visited)
  }

  @Test fun aCandidateHopLimitDoesNotDiscardItsWorkingSibling() {
    for (redirect in listOf(false, true)) {
      val visited = mutableListOf<String>()
      val replies = mutableMapOf(
        "/root.m3u" to Reply("first0.m3u\nworking.mp3", mime = "audio/x-mpegurl"),
        "/working.mp3" to Reply("audio"),
      )
      for (index in 0 until AlarmStreamResolver.MAX_HOPS) {
        replies["/first$index.m3u"] = if (redirect) {
          Reply("", code = 302, location = "first${index + 1}.m3u")
        } else Reply("first${index + 1}.m3u", mime = "audio/x-mpegurl")
      }
      assertEquals("https://radio.test/working.mp3",
        AlarmStreamResolver.resolveWithClient("https://radio.test/root.m3u", fixture(replies, visited)).url)
      assertEquals(listOf("/root.m3u") + (0 until AlarmStreamResolver.MAX_HOPS).map { "/first$it.m3u" } +
        "/working.mp3", visited)
    }
  }

  @Test fun anOversizedNestedPlaylistDoesNotDiscardItsWorkingSibling() {
    val visited = mutableListOf<String>()
    val client = fixture(mapOf(
      "/root.m3u" to Reply("oversized.m3u\nworking.mp3", mime = "audio/x-mpegurl"),
      "/oversized.m3u" to Reply("x".repeat(AlarmStreamResolver.MAX_PLAYLIST_BYTES + 1), mime = "audio/x-mpegurl"),
      "/working.mp3" to Reply("audio"),
    ), visited)
    assertEquals("https://radio.test/working.mp3",
      AlarmStreamResolver.resolveWithClient("https://radio.test/root.m3u", client).url)
    assertEquals(listOf("/root.m3u", "/oversized.m3u", "/working.mp3"), visited)
  }

  @Test fun aDisconnectedPlaylistBodyDoesNotDiscardItsWorkingSibling() {
    val visited = mutableListOf<String>()
    val client = fixture(mapOf(
      "/root.m3u" to Reply("broken.m3u\nworking.mp3", mime = "audio/x-mpegurl"),
      "/broken.m3u" to Reply("", mime = "audio/x-mpegurl", brokenRead = true),
      "/working.mp3" to Reply("audio"),
    ), visited)
    assertEquals("https://radio.test/working.mp3",
      AlarmStreamResolver.resolveWithClient("https://radio.test/root.m3u", client).url)
    assertEquals(listOf("/root.m3u", "/broken.m3u", "/working.mp3"), visited)
  }

  @Test fun nestedCandidateRecoveryDoesNotResetTheGlobalRequestBudget() {
    val visited = mutableListOf<String>()
    val replies = mutableMapOf<String, Reply>(
      "/root.m3u" to Reply((0..20).joinToString("\n") { "child$it.m3u" }, mime = "audio/x-mpegurl"),
    )
    for (index in 0..20) {
      replies["/child$index.m3u"] = Reply("dead$index.mp3", mime = "audio/x-mpegurl")
      replies["/dead$index.mp3"] = Reply("Unavailable", code = 404)
    }
    try {
      AlarmStreamResolver.resolveWithClient("https://radio.test/root.m3u", fixture(replies, visited))
      fail("The request budget must end traversal")
    } catch (error: AlarmStreamResolutionException) {
      assertTrue(error.message!!.contains("too many stream addresses"))
      assertEquals(AlarmStreamResolver.MAX_REQUESTS, visited.size)
    }
  }

  @Test fun nestedCandidateRecoveryDoesNotResetTheGlobalTimeBudget() {
    val visited = mutableListOf<String>()
    var nowNanos = 0L
    val client = fixture(mapOf(
      "/root.m3u" to Reply("child.m3u\nworking.mp3", mime = "audio/x-mpegurl"),
      "/child.m3u" to Reply("dead.mp3", mime = "audio/x-mpegurl"),
      "/dead.mp3" to Reply("Unavailable", code = 404),
    ), visited) { nowNanos += 4_000_000_000L }
    try {
      AlarmStreamResolver.resolveWithClient("https://radio.test/root.m3u", client) { nowNanos }
      fail("The global deadline must end traversal")
    } catch (error: AlarmStreamResolutionException) {
      assertTrue(error.message!!.contains("too long"))
      assertEquals(listOf("/root.m3u", "/child.m3u", "/dead.mp3"), visited)
    }
  }

  @Test fun fixtureClientCannotBypassInitialPrivateDestinationValidation() {
    val visited = mutableListOf<String>()
    try {
      AlarmStreamResolver.resolveWithClient("http://127.0.0.1/root.m3u", fixture(emptyMap(), visited))
      fail("Loopback must remain forbidden")
    } catch (_: AlarmStreamResolutionException) {
      assertTrue(visited.isEmpty())
    }
  }

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
