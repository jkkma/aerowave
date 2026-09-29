package com.aerowave.audio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Per-ring debug trace. The service creates this only for a debuggable APK. */
internal class AlarmStartupDiagnostics(context: Context) {
  private val captureDirectory = File(context.filesDir, "alarm-diagnostics")

  private class Attempt(
    val ringId: String,
    val number: Int,
    val startedMs: Long,
    val kind: String,
    val isHls: Boolean,
  ) {
    val calls = AtomicInteger()
    val bytes = AtomicLong()
    val firstByteMs = AtomicLong(-1)
    val firstAudioPosition = AtomicLong(-1)
    @Volatile var networkPhase = "none"
    @Volatile var httpStatus = "none"
    @Volatile var contentType = "none"
    @Volatile var icyMetaInt = "none"
    @Volatile var extractorPhase = "none"
    @Volatile var audioFormat = "none"
    @Volatile var decoder = "none"
    @Volatile var playerState = "idle"
    @Volatile var capture: Capture? = null
    var lastWatchdogLogMs = -3_000L // Only the main-thread watchdog changes this.
  }

  @Volatile private var ringId = "none"
  @Volatile private var active: Attempt? = null
  private var attemptNumber = 0 // AlarmPlaybackService changes this on its main thread.
  private var preparingOccurrenceId: String? = null

  fun beginPreparation(occurrenceId: String) {
    active?.capture?.flush("prewarm_replaced")
    ringId = ringKey(occurrenceId)
    attemptNumber = 0
    active = null
    preparingOccurrenceId = occurrenceId
    ringEvent("prewarm_start")
  }

  fun startRing(
    occurrenceId: String,
    trigger: String,
    focusGranted: Boolean,
    preserveActive: Boolean = false,
  ) {
    val samePreparation = preparingOccurrenceId == occurrenceId
    if (!preserveActive || !samePreparation) {
      active?.capture?.flush("prewarm_not_adopted")
      active = null
    }
    if (!samePreparation) {
      ringId = ringKey(occurrenceId)
      attemptNumber = 0
    }
    preparingOccurrenceId = null
    ringEvent("ring_start", "trigger=$trigger focusGranted=$focusGranted")
  }

  fun finishRing(action: String) {
    active?.capture?.flush("ring_finish_$action")
    event("ring_finish", "action=$action")
    active = null
    preparingOccurrenceId = null
  }

  fun serviceDestroy() {
    active?.capture?.flush("service_destroy")
    event("service_destroy")
  }

  fun resolveStart(rawUrl: String): Long {
    ringEvent("resolve_start", "uri=${endpoint(rawUrl)}")
    return SystemClock.elapsedRealtime()
  }

  fun resolveResult(startedMs: Long, rawUrl: String?, isHls: Boolean?, error: Throwable?) {
    ringEvent(
      "resolve_end",
      "durationMs=${SystemClock.elapsedRealtime() - startedMs} " +
        if (error == null) "result=ok hls=$isHls uri=${endpoint(rawUrl)}"
        else "result=error type=${errorType(error)}",
    )
  }

  fun sourceStart(kind: String, rawUri: String, isHls: Boolean) {
    active?.capture?.flush("source_replaced")
    val attempt = Attempt(ringId, ++attemptNumber, SystemClock.elapsedRealtime(), kind, isHls)
    if (kind == "station" && !isHls) attempt.capture = Capture(attempt, endpoint(rawUri))
    active = attempt
    log(attempt, "source_start", "kind=$kind hls=$isHls uri=${endpoint(rawUri)}")
  }

  fun event(stage: String, detail: String = "") {
    val attempt = active
    if (attempt == null) ringEvent(stage, detail) else log(attempt, stage, detail)
  }

  fun playerState(state: Int, isLoading: Boolean, isPlaying: Boolean) {
    val attempt = active ?: return
    attempt.playerState = when (state) {
      Player.STATE_IDLE -> "idle"
      Player.STATE_BUFFERING -> "buffering"
      Player.STATE_READY -> "ready"
      Player.STATE_ENDED -> "ended"
      else -> "unknown($state)"
    }
    log(attempt, "player_state", "state=${attempt.playerState} loading=$isLoading playing=$isPlaying")
  }

  fun playerLoading(isLoading: Boolean) = event("player_loading", "loading=$isLoading")

  fun playerPlaying(isPlaying: Boolean, positionMs: Long) =
    event("player_playing", "playing=$isPlaying positionMs=$positionMs")

  fun tracks(tracks: Tracks) {
    val attempt = active ?: return
    if (attempt.kind != "station") return
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val selected = audio.flatMap { group ->
      (0 until group.length).filter { group.isTrackSelected(it) }.map { index ->
        "supported=${group.isTrackSupported(index)} ${format(group.getTrackFormat(index))}"
      }
    }
    log(attempt, "tracks", "audioGroups=${audio.size} selected=${selected.ifEmpty { listOf("none") }.joinToString("|")}")
  }

  fun audioFormat(format: Format?) {
    val attempt = active ?: return
    attempt.audioFormat = format(format)
    log(attempt, "audio_format", attempt.audioFormat)
  }

  fun decoder(name: String, initializationMs: Long) {
    val attempt = active ?: return
    attempt.decoder = name
    log(attempt, "audio_decoder", "name=$name initializationMs=$initializationMs")
  }

  fun audioPositionAdvancing(systemTimeMs: Long) {
    val attempt = active ?: return
    if (attempt.firstAudioPosition.compareAndSet(-1, systemTimeMs)) {
      log(attempt, "audio_position_advancing", "systemTimeMs=$systemTimeMs")
    }
  }

  fun extractorProbe(candidate: String, stage: String, result: String, durationMs: Long) {
    val attempt = stationAttempt() ?: return
    attempt.extractorPhase = "$candidate:$stage:$result"
    log(attempt, "extractor_$stage", "candidate=$candidate result=$result durationMs=$durationMs")
  }

  fun aacAlignment(stage: String, offset: Int?, durationMs: Long, error: String?) {
    val attempt = stationAttempt() ?: return
    attempt.extractorPhase = "aac_align:$stage:${offset ?: "none"}"
    log(attempt, "aac_align_$stage", "offset=${offset ?: "none"} durationMs=$durationMs error=${error ?: "none"}")
  }

  fun mediaLoad(stage: String, rawUri: String, detail: String) {
    val attempt = stationAttempt() ?: return
    log(attempt, "media_load_$stage", "uri=${endpoint(rawUri)} $detail")
  }

  fun mediaError(stage: String, error: Throwable, detail: String = "") {
    val attempt = stationAttempt() ?: return
    log(attempt, stage, "type=${errorType(error)} $detail")
    if (stage == "player_error" || stage == "media_load_error" || stage == "analytics_player_error") {
      attempt.capture?.flush(stage)
    }
  }

  fun analyticsListener(): AnalyticsListener = object : AnalyticsListener {
    override fun onLoadStarted(
      eventTime: AnalyticsListener.EventTime,
      loadEventInfo: LoadEventInfo,
      mediaLoadData: MediaLoadData,
      retryCount: Int,
    ) = mediaLoad("start", loadEventInfo.uri.toString(),
      "dataType=${mediaLoadData.dataType} retry=$retryCount")

    override fun onLoadCompleted(
      eventTime: AnalyticsListener.EventTime,
      loadEventInfo: LoadEventInfo,
      mediaLoadData: MediaLoadData,
    ) = mediaLoad("completed", loadEventInfo.uri.toString(),
      "bytes=${loadEventInfo.bytesLoaded} durationMs=${loadEventInfo.loadDurationMs}")

    override fun onLoadError(
      eventTime: AnalyticsListener.EventTime,
      loadEventInfo: LoadEventInfo,
      mediaLoadData: MediaLoadData,
      error: IOException,
      wasCanceled: Boolean,
    ) = mediaError("media_load_error", error,
      "uri=${endpoint(loadEventInfo.uri.toString())} bytes=${loadEventInfo.bytesLoaded} canceled=$wasCanceled")

    override fun onDownstreamFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      mediaLoadData: MediaLoadData,
    ) {
      if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO) audioFormat(mediaLoadData.trackFormat)
    }

    override fun onAudioDecoderInitialized(
      eventTime: AnalyticsListener.EventTime,
      decoderName: String,
      initializedTimestampMs: Long,
      initializationDurationMs: Long,
    ) = decoder(decoderName, initializationDurationMs)

    override fun onAudioInputFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      format: Format,
      decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) = audioFormat(format)

    override fun onAudioPositionAdvancing(
      eventTime: AnalyticsListener.EventTime,
      playoutStartSystemTimeMs: Long,
    ) = audioPositionAdvancing(playoutStartSystemTimeMs)

    override fun onAudioUnderrun(
      eventTime: AnalyticsListener.EventTime,
      bufferSize: Int,
      bufferSizeMs: Long,
      elapsedSinceLastFeedMs: Long,
    ) = mediaLoad("audio_underrun", "", "bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, error: Exception) =
      mediaError("audio_codec_error", error)

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, error: Exception) =
      mediaError("audio_sink_error", error)

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
      mediaError("analytics_player_error", error, "code=${error.errorCodeName}")
  }

  fun watchdog(
    positionMs: Long,
    bufferedPositionMs: Long,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    isLoading: Boolean,
    focusPaused: Boolean,
    failure: String?,
  ) {
    val attempt = active ?: return
    val elapsed = SystemClock.elapsedRealtime() - attempt.startedMs
    if (failure == null && elapsed - attempt.lastWatchdogLogMs < 3_000) return
    attempt.lastWatchdogLogMs = elapsed
    log(
      attempt, if (failure == null) "watchdog_snapshot" else "watchdog_failure",
      "positionMs=$positionMs bufferedMs=$bufferedPositionMs isPlaying=$isPlaying " +
        "playWhenReady=$playWhenReady loading=$isLoading focusPaused=$focusPaused " +
        "bytes=${attempt.bytes.get()} firstByteMs=${attempt.firstByteMs.get()} " +
        "network=${attempt.networkPhase} status=${attempt.httpStatus} contentType=${attempt.contentType} " +
        "icyMetaInt=${attempt.icyMetaInt} captureBytes=${attempt.capture?.size() ?: 0} " +
        "extractor=${attempt.extractorPhase} format=${attempt.audioFormat} decoder=${attempt.decoder} " +
        "player=${attempt.playerState} decision=${reasonCode(failure)}",
    )
  }

  fun failureDecision(reason: String, fallbackStarted: Boolean, kind: String) {
    event("fallback_decision", "reason=${reasonCode(reason)} kind=$kind fallbackStarted=$fallbackStarted")
    active?.capture?.flush("fallback_${reasonCode(reason)}")
  }

  fun withHttpEvents(client: OkHttpClient): OkHttpClient =
    client.newBuilder().eventListenerFactory(EventListener.Factory { call ->
      val attempt = stationAttempt()
      if (attempt == null) object : EventListener() {}
      else NetworkEvents(attempt, attempt.calls.incrementAndGet(), endpoint(call.request().url.toString()))
    }).build()

  fun withTransferEvents(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
    val source = factory.createDataSource()
    val attempt = stationAttempt()
    if (attempt == null) source else {
      source.addTransferListener(object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
          log(attempt, "transfer_init", "network=$isNetwork uri=${endpoint(dataSpec.uri.toString())}")
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
          log(attempt, "transfer_start", "network=$isNetwork uri=${endpoint(dataSpec.uri.toString())}")
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
          attempt.bytes.addAndGet(bytesTransferred.toLong())
          val elapsed = SystemClock.elapsedRealtime() - attempt.startedMs
          if (attempt.firstByteMs.compareAndSet(-1, elapsed)) {
            log(attempt, "first_byte", "bytes=$bytesTransferred network=$isNetwork")
          }
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
          log(attempt, "transfer_end", "bytes=${attempt.bytes.get()} network=$isNetwork")
          attempt.capture?.flush("transfer_end")
        }
      })
      // This sits before IcyDataSource and extractor sniffing, so the bounded
      // file contains the exact radio bytes Media3 first received.
      object : DataSource {
        override fun open(dataSpec: DataSpec): Long = source.open(dataSpec)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
          source.read(buffer, offset, length).also { count ->
            if (count > 0) attempt.capture?.record(buffer, offset, count)
          }
        override fun getUri(): Uri? = source.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
        override fun close() = source.close()
        override fun addTransferListener(listener: TransferListener) = source.addTransferListener(listener)
      }
    }
  }

  private inner class Capture(private val attempt: Attempt, private val sanitizedEndpoint: String) {
    private val bytes = ByteArrayOutputStream(CAPTURE_LIMIT_BYTES)
    private val baseName = "alarm-${attempt.ringId}-a${attempt.number}"
    private var flushed = false

    @Synchronized fun size(): Int = bytes.size()

    @Synchronized fun record(buffer: ByteArray, offset: Int, count: Int) {
      if (flushed) return
      val copy = count.coerceAtMost(CAPTURE_LIMIT_BYTES - bytes.size())
      if (copy > 0) bytes.write(buffer, offset, copy)
      if (bytes.size() == CAPTURE_LIMIT_BYTES) flush("limit")
    }

    @Synchronized fun flush(reason: String) {
      if (flushed) return
      flushed = true
      try {
        if (!captureDirectory.isDirectory && !captureDirectory.mkdirs()) {
          throw IOException("Could not create private alarm diagnostics directory")
        }
        val payload = File(captureDirectory, "$baseName.bin")
        val sidecar = File(captureDirectory, "$baseName.json")
        if (!payload.createNewFile() || !sidecar.createNewFile()) {
          throw IOException("Alarm diagnostic capture filename already exists")
        }
        FileOutputStream(payload).use { output ->
          bytes.writeTo(output)
          output.fd.sync()
        }
        val metadata = JSONObject()
          .put("ring", attempt.ringId)
          .put("attempt", attempt.number)
          .put("endpoint", sanitizedEndpoint)
          .put("capturedBytes", bytes.size())
          .put("observedBytes", attempt.bytes.get())
          .put("httpStatus", attempt.httpStatus)
          .put("contentType", attempt.contentType)
          .put("icyMetaInt", attempt.icyMetaInt)
          .put("networkPhase", attempt.networkPhase)
          .put("extractorPhase", attempt.extractorPhase)
          .put("audioFormat", attempt.audioFormat)
          .put("decoder", attempt.decoder)
          .put("flushReason", reason)
        FileOutputStream(sidecar).use { output ->
          output.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
          output.fd.sync()
        }
        log(attempt, "capture_flushed", "file=${payload.name} metadata=${sidecar.name} " +
          "bytes=${bytes.size()} reason=$reason")
      } catch (error: Exception) {
        log(attempt, "capture_error", "type=${errorType(error)} reason=$reason")
      }
    }
  }

  private fun stationAttempt(): Attempt? = active?.takeIf { it.kind == "station" && !it.isHls }

  private fun ringKey(occurrenceId: String): String =
    "${SystemClock.elapsedRealtime().toString(36)}-${occurrenceId.hashCode().toUInt().toString(16)}"

  private fun ringEvent(stage: String, detail: String = "") {
    Log.d(TAG, "ring=$ringId attempt=0 t=+0ms stage=$stage $detail")
  }

  private fun log(attempt: Attempt, stage: String, detail: String) {
    Log.d(TAG, "ring=${attempt.ringId} attempt=${attempt.number} " +
      "t=+${SystemClock.elapsedRealtime() - attempt.startedMs}ms stage=$stage $detail")
  }

  private inner class NetworkEvents(
    private val attempt: Attempt,
    private val callId: Int,
    private val initialEndpoint: String,
  ) : EventListener() {
    private fun network(stage: String, detail: String = "") {
      attempt.networkPhase = stage
      log(attempt, "http_$stage", "call=$callId $detail")
    }

    override fun callStart(call: Call) = network("call_start", "uri=$initialEndpoint")
    override fun dnsStart(call: Call, domainName: String) = network("dns_start", "host=$domainName")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
      network("dns_end", "host=$domainName addresses=${inetAddressList.size}")
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
      network("connect_start", "host=${inetSocketAddress.hostString} port=${inetSocketAddress.port}")
    override fun secureConnectStart(call: Call) = network("tls_start")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = network("tls_end", "tls=${handshake?.tlsVersion}")
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
      network("connect_end", "protocol=$protocol")
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
      network("connect_failed", "type=${errorType(ioe)}")
    override fun requestHeadersStart(call: Call) = network("request_start")
    override fun requestHeadersEnd(call: Call, request: Request) =
      network("request_end", "uri=${endpoint(request.url.toString())}")
    override fun responseHeadersStart(call: Call) = network("headers_start")
    override fun responseHeadersEnd(call: Call, response: Response) {
      attempt.httpStatus = response.code.toString()
      attempt.contentType = response.header("content-type")?.substringBefore(';')
        ?.filter { it.isLetterOrDigit() || it in "/.+-" }?.take(64) ?: "none"
      attempt.icyMetaInt = response.header("icy-metaint")?.toIntOrNull()?.toString() ?: "none"
      network("headers_end", "status=${response.code} contentType=${attempt.contentType} " +
        "icyMetaInt=${attempt.icyMetaInt} uri=${endpoint(response.request.url.toString())}")
    }
    override fun responseBodyStart(call: Call) = network("body_start")
    override fun callEnd(call: Call) = network("call_end", "bytes=${attempt.bytes.get()}")
    override fun callFailed(call: Call, ioe: IOException) = network("call_failed", "type=${errorType(ioe)}")
  }

  companion object {
    private const val TAG = "AerowaveAlarmDiag"
    private const val CAPTURE_LIMIT_BYTES = 64 * 1024

    fun endpoint(raw: String?): String {
      if (raw.isNullOrBlank()) return "none"
      return try {
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase() ?: return "unknown"
        if (scheme != "http" && scheme != "https") return "$scheme:"
        val host = uri.host ?: return "$scheme://unknown"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        "$scheme://$host$port${uri.encodedPath.orEmpty().take(160)}"
      } catch (_: Exception) { "invalid" }
    }

    fun errorType(error: Throwable): String = buildString {
      append(error.javaClass.simpleName)
      error.cause?.takeIf { it !== error }?.let { append('/'); append(it.javaClass.simpleName) }
    }

    private fun reasonCode(reason: String?): String = when {
      reason == null -> "none"
      reason.contains("connected but did not produce audio") -> "startup_timeout"
      reason.contains("stopped producing audio") -> "stall_timeout"
      reason.contains("no playable audio track") -> "no_playable_audio_track"
      reason.contains("station stopped playing") -> "station_ended"
      else -> "source_failure"
    }

    private fun format(format: Format?): String = if (format == null) "none" else
      "mime=${format.sampleMimeType} codecs=${format.codecs} rate=${format.sampleRate} " +
        "channels=${format.channelCount} bitrate=${format.bitrate}"
  }
}
