package com.aerowave.audio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

internal data class ResolvedAlarmStream(
  val url: String,
  val isHls: Boolean,
)

internal open class AlarmStreamResolutionException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

internal enum class AlarmPlaylistKind { NONE, PLS, M3U, ASX }

internal object AlarmPlaylistParser {
  private val asxReference = Regex(
    """<\s*ref\b[^>]*\bhref\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
  )
  private val asxIniReference = Regex("""^\s*ref\d*\s*=\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

  fun kind(url: String, contentType: String?): AlarmPlaylistKind {
    val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return when {
      path.endsWith(".pls") || mime.contains("scpls") || mime.contains("pls+xml") ->
        AlarmPlaylistKind.PLS
      path.endsWith(".asx") || mime.contains("ms-asf") || mime.contains("x-ms-asx") ->
        AlarmPlaylistKind.ASX
      path.endsWith(".m3u") || path.endsWith(".m3u8") || mime.contains("mpegurl") ->
        AlarmPlaylistKind.M3U
      else -> AlarmPlaylistKind.NONE
    }
  }

  fun isHtml(contentType: String?): Boolean {
    val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    return mime == "text/html" || mime == "application/xhtml+xml"
  }

  fun isHls(body: String): Boolean {
    val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    return lines.firstOrNull()?.equals("#EXTM3U", ignoreCase = true) == true &&
      lines.any { it.startsWith("#EXT-X-", ignoreCase = true) }
  }

  fun entries(kind: AlarmPlaylistKind, body: String): List<String> {
    val entries = when (kind) {
      AlarmPlaylistKind.NONE -> emptyList()
      AlarmPlaylistKind.PLS -> body.lineSequence().mapNotNull { line ->
        val (key, value) = line.splitOnce('=') ?: return@mapNotNull null
        value.trim().takeIf {
          key.trim().matches(Regex("file\\d+", RegexOption.IGNORE_CASE)) && it.isNotEmpty()
        }
      }.toList()
      AlarmPlaylistKind.M3U -> body.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith(';') }
        .toList()
      AlarmPlaylistKind.ASX -> buildList {
        asxReference.findAll(body).forEach { match ->
          match.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.let {
            add(decodeXmlEntities(it.trim()))
          }
        }
        body.lineSequence().forEach { line ->
          asxIniReference.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
            ?.let(::add)
        }
      }
    }
    return entries.distinct().take(MAX_CANDIDATES)
  }

  private fun String.splitOnce(delimiter: Char): Pair<String, String>? {
    val at = indexOf(delimiter)
    if (at < 0) return null
    return substring(0, at) to substring(at + 1)
  }

  private fun decodeXmlEntities(value: String): String = value
    .replace("&amp;", "&", ignoreCase = true)
    .replace("&quot;", "\"", ignoreCase = true)
    .replace("&apos;", "'", ignoreCase = true)
    .replace("&lt;", "<", ignoreCase = true)
    .replace("&gt;", ">", ignoreCase = true)

  private const val MAX_CANDIDATES = 64
}

/**
 * Resolves the wrappers Media3 cannot play by itself. This is deliberately a
 * blocking API: callers run it on their alarm/source executor and fence the
 * result before handing the final URL back to the player thread.
 */
internal object AlarmStreamResolver {
  const val MAX_HOPS = 5
  const val MAX_REQUESTS = 12
  const val MAX_PLAYLIST_BYTES = 256 * 1024
  const val MAX_TOTAL_DURATION_MS = 10_000L
  private const val MAX_CONNECT_OR_READ_MS = 5_000L

  fun resolve(url: String, userAgent: String): ResolvedAlarmStream {
    val initial = url.toHttpUrlOrNull()
      ?: throw AlarmStreamResolutionException("The station URL is invalid")
    val baseClient = NetworkGuard.client(false, userAgent).newBuilder()
      // Count and validate redirects in the same budget as playlist hops.
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
    return Session(baseClient, System.nanoTime()).resolve(initial, 0)
  }

  private class Session(
    private val baseClient: okhttp3.OkHttpClient,
    private val startedAtNanos: Long,
  ) {
    private val visited = mutableSetOf<String>()
    private var requests = 0

    fun resolve(url: HttpUrl, hop: Int): ResolvedAlarmStream {
      if (hop > MAX_HOPS) {
        throw AlarmStreamResolutionException("The station playlist contains too many redirects")
      }
      if (++requests > MAX_REQUESTS) {
        throw AlarmStreamResolutionException("The station playlist tried too many stream addresses")
      }
      val key = url.toString()
      if (!visited.add(key)) {
        throw CandidateFailure("The station playlist contains a loop")
      }
      try {
        NetworkGuard.validateInitialUrl(key, allowLoopback = false)
      } catch (error: Exception) {
        throw CandidateFailure(error.message ?: "The station points to a non-public address", error)
      }

      val remaining = remainingMs()
      val ioTimeout = minOf(remaining, MAX_CONNECT_OR_READ_MS)
      val client = baseClient.newBuilder()
        .connectTimeout(ioTimeout, TimeUnit.MILLISECONDS)
        .readTimeout(ioTimeout, TimeUnit.MILLISECONDS)
        .callTimeout(remaining, TimeUnit.MILLISECONDS)
        .build()
      val request = Request.Builder()
        .url(url)
        .header("Accept", "audio/*,application/vnd.apple.mpegurl,application/x-mpegurl,*/*;q=0.1")
        .build()

      val response = try {
        client.newCall(request).execute()
      } catch (error: IOException) {
        if (remainingMsOrZero() == 0L) {
          throw AlarmStreamResolutionException("Resolving that station took too long", error)
        }
        throw CandidateFailure(error.message ?: "The station did not answer", error)
      }
      response.use {
        remainingMs()
        if (it.code in 300..399) return followRedirect(it, hop)
        if (!it.isSuccessful) {
          throw CandidateFailure("The station answered with HTTP ${it.code}")
        }

        val finalUrl = it.request.url
        val contentType = it.header("Content-Type")
        if (AlarmPlaylistParser.isHtml(contentType)) {
          throw CandidateFailure("The station returned a web page instead of audio")
        }
        val kind = AlarmPlaylistParser.kind(finalUrl.toString(), contentType)
        if (kind == AlarmPlaylistKind.NONE) {
          // Closing an unconsumed body leaves stream bytes to Media3 instead
          // of spending the resolver's budget reading live audio.
          return ResolvedAlarmStream(finalUrl.toString(), isHls = false)
        }

        val body = readPlaylist(it)
        if (AlarmPlaylistParser.isHls(body)) {
          return ResolvedAlarmStream(finalUrl.toString(), isHls = true)
        }
        val entries = AlarmPlaylistParser.entries(kind, body)
        if (entries.isEmpty()) {
          throw CandidateFailure("The station playlist contains no playable stream address")
        }

        var lastFailure: Throwable? = null
        for (entry in entries) {
          val candidate = finalUrl.resolve(entry)
          if (candidate == null) {
            lastFailure = CandidateFailure("The station playlist contains an invalid stream address")
            continue
          }
          try {
            return resolve(candidate, hop + 1)
          } catch (error: CandidateFailure) {
            lastFailure = error
          }
        }
        throw AlarmStreamResolutionException(
          "The station playlist did not lead to a playable public stream",
          lastFailure,
        )
      }
    }

    private fun followRedirect(response: Response, hop: Int): ResolvedAlarmStream {
      if (hop >= MAX_HOPS) {
        throw AlarmStreamResolutionException("The station contains too many redirects")
      }
      val location = response.header("Location")
        ?: throw CandidateFailure("The station returned a redirect without a destination")
      val next = response.request.url.resolve(location)
        ?: throw CandidateFailure("The station returned an invalid redirect")
      return resolve(next, hop + 1)
    }

    private fun readPlaylist(response: Response): String {
      val body = response.body ?: throw CandidateFailure("The station playlist was empty")
      val declared = body.contentLength()
      if (declared > MAX_PLAYLIST_BYTES) {
        throw AlarmStreamResolutionException("The station playlist is larger than 256 KiB")
      }
      val output = ByteArrayOutputStream(
        if (declared in 1..MAX_PLAYLIST_BYTES.toLong()) declared.toInt() else 8 * 1024,
      )
      val input = body.byteStream()
      val buffer = ByteArray(8 * 1024)
      while (true) {
        remainingMs()
        val allowed = MAX_PLAYLIST_BYTES + 1 - output.size()
        if (allowed <= 0) {
          throw AlarmStreamResolutionException("The station playlist is larger than 256 KiB")
        }
        val read = input.read(buffer, 0, minOf(buffer.size, allowed))
        if (read < 0) break
        output.write(buffer, 0, read)
        if (output.size() > MAX_PLAYLIST_BYTES) {
          throw AlarmStreamResolutionException("The station playlist is larger than 256 KiB")
        }
      }
      return output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }

    private fun remainingMs(): Long {
      val remaining = remainingMsOrZero()
      if (remaining <= 0) throw AlarmStreamResolutionException("Resolving that station took too long")
      return remaining
    }

    private fun remainingMsOrZero(): Long {
      val elapsedNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0)
      return (MAX_TOTAL_DURATION_MS - TimeUnit.NANOSECONDS.toMillis(elapsedNanos)).coerceAtLeast(0)
    }
  }

  private class CandidateFailure(message: String, cause: Throwable? = null) :
    AlarmStreamResolutionException(message, cause)
}
