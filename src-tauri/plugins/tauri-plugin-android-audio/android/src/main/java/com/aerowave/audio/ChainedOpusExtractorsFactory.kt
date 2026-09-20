package com.aerowave.audio

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.OpusUtil
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ogg.OggExtractor
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * Repairs same-format chained Opus streams that Media3's Ogg extractor reads as
 * one logical stream. At a link boundary Media3 1.11.1 emits the next link's
 * OpusHead and OpusTags packets as audio and advances its timestamp for both.
 */
internal class ChainedOpusExtractorsFactory(
  delegate: ExtractorsFactory = DefaultExtractorsFactory(),
  private val onStreamMetadata: (OpusStreamMetadata) -> Unit = {},
) : ForwardingExtractorsFactory(delegate) {
  override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())

  override fun createExtractors(
    uri: Uri,
    responseHeaders: Map<String, List<String>>,
  ): Array<Extractor> = wrap(super.createExtractors(uri, responseHeaders))

  private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
    extractors.map {
      if (it is OggExtractor) ChainedOpusExtractor(it, onStreamMetadata) else it
    }.toTypedArray()
}

private class ChainedOpusExtractor(
  delegate: Extractor,
  private val onStreamMetadata: (OpusStreamMetadata) -> Unit,
) : ForwardingExtractor(delegate) {
  private var output: ChainedOpusExtractorOutput? = null

  override fun init(output: ExtractorOutput) {
    val filtered = ChainedOpusExtractorOutput(output, onStreamMetadata)
    this.output = filtered
    super.init(filtered)
  }

  override fun seek(position: Long, timeUs: Long) {
    output?.reset()
    super.seek(position, timeUs)
  }
}

private class ChainedOpusExtractorOutput(
  delegate: ExtractorOutput,
  private val onStreamMetadata: (OpusStreamMetadata) -> Unit,
) : ForwardingExtractorOutput(delegate) {
  private val tracks = mutableMapOf<Int, ChainedOpusTrackOutput>()
  private var filteringEnabled = false

  override fun track(id: Int, type: Int): TrackOutput {
    val delegate = super.track(id, type)
    if (type != C.TRACK_TYPE_AUDIO) return delegate
    return tracks.getOrPut(id) {
      ChainedOpusTrackOutput(
        delegate,
        filteringEnabled,
        onChainHandled = { chain, adjustmentUs ->
          Log.i(
            TAG,
            "Handled chained Opus link $chain: suppressed OpusHead=1 OpusTags=1, " +
              "adjusted timestamps by ${adjustmentUs}us",
          )
        },
        onStreamMetadata = onStreamMetadata,
      )
    }
  }

  override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) {
    filteringEnabled = !seekMap.isSeekable
    tracks.values.forEach { it.setFilteringEnabled(filteringEnabled) }
    super.seekMap(seekMap)
  }

  fun reset() = tracks.values.forEach(ChainedOpusTrackOutput::reset)

  private companion object {
    const val TAG = "AerowaveChainedOpus"
  }
}

internal class ChainedOpusTrackOutput(
  delegate: TrackOutput,
  filteringEnabled: Boolean = true,
  private val onChainHandled: (Int, Long) -> Unit = { _, _ -> },
  private val onStreamMetadata: (OpusStreamMetadata) -> Unit = {},
) : ForwardingTrackOutput(delegate) {
  private val pending = ByteArrayOutputStream()
  private var initialHeader: ByteArray? = null
  private var opusFormat: Format? = null
  private var isOpus = false
  private var filteringEnabled = filteringEnabled
  private var removedDurationUs = 0L
  private var chainCount = 0
  private var awaitingTags = false
  private var activeChainAdjustmentUs = 0L
  private var activeChainBoundaryUs = 0L
  private var forwardedBytes = 0

  override fun format(format: Format) {
    isOpus = format.sampleMimeType == MimeTypes.AUDIO_OPUS
    if (isOpus) {
      val header = format.initializationData.firstOrNull()
        ?: throw IllegalStateException("The Opus stream has no decoder configuration")
      val existing = initialHeader
      if (existing == null) initialHeader = header.clone()
      else if (!existing.contentEquals(header)) {
        throw UnsupportedChainedOpusConfigurationException()
      }
      opusFormat = format
    }
    super.format(format)
  }

  override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
    if (!shouldFilter()) super.sampleData(input, length, allowEndOfInput)
    else capture(input, length, allowEndOfInput)

  override fun sampleData(
    input: DataReader,
    length: Int,
    allowEndOfInput: Boolean,
    sampleDataPart: Int,
  ): Int {
    if (!shouldFilter()) return super.sampleData(input, length, allowEndOfInput, sampleDataPart)
    requireMainData(sampleDataPart)
    return capture(input, length, allowEndOfInput)
  }

  override fun sampleData(data: ParsableByteArray, length: Int) {
    if (!shouldFilter()) {
      super.sampleData(data, length)
      return
    }
    captureCandidateOrForward(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
  }

  override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
    if (!shouldFilter()) {
      super.sampleData(data, length, sampleDataPart)
      return
    }
    requireMainData(sampleDataPart)
    captureCandidateOrForward(data, length, sampleDataPart)
  }

  override fun sampleMetadata(
    timeUs: Long,
    flags: Int,
    size: Int,
    offset: Int,
    cryptoData: TrackOutput.CryptoData?,
  ) {
    if (!shouldFilter()) {
      super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
      return
    }
    check(offset == 0) { "Chained Opus filtering requires zero sample metadata offset" }
    check(size == pending.size() + forwardedBytes) {
      "Chained Opus sample size $size does not match " +
        "${pending.size()} buffered and $forwardedBytes forwarded bytes"
    }

    if (pending.size() == 0) {
      super.sampleMetadata(timeUs - removedDurationUs, flags, size, 0, cryptoData)
      forwardedBytes = 0
      return
    }
    check(forwardedBytes == 0) { "A chained Opus packet used mixed sample-data paths" }
    val packet = pending.toByteArray()
    pending.reset()
    when {
      isOpusHead(packet) -> {
        check(!awaitingTags) { "The chained Opus link repeated OpusHead before OpusTags" }
        val configuredHeader = initialHeader
          ?: throw IllegalStateException("The Opus stream has no decoder configuration")
        if (!packet.contentEquals(configuredHeader)) {
          throw UnsupportedChainedOpusConfigurationException()
        }
        activeChainBoundaryUs = timeUs - removedDurationUs
        val preSkipSamples = OpusUtil.getPreSkipSamples(packet)
        if (preSkipSamples != REQUIRED_PRE_SKIP_SAMPLES) {
          throw UnsupportedChainedOpusConfigurationException(
            "The chained Opus link uses unsupported pre-skip $preSkipSamples",
          )
        }
        chainCount++
        // A distinct id makes SampleQueue publish a new Format while keeping
        // the codec configuration identical. Media3 resets the Opus decoder
        // by flushing or recreating it, so the new link's pre-roll is discarded.
        val refreshedFormat = opusFormat
          ?.buildUpon()
          ?.setId("aerowave-opus-chain-$chainCount")
          ?.build()
          ?: throw IllegalStateException("The Opus stream has no active format")
        opusFormat = refreshedFormat
        super.format(refreshedFormat)
        // The encoded pre-roll intentionally starts 80 ms before the audible
        // boundary. Those input timestamps may overlap the old link; after the
        // decoder reset discards pre-roll, decoded output remains continuous.
        activeChainAdjustmentUs = OpusUtil.getPacketDurationUs(packet) + PRE_SKIP_DURATION_US
        removedDurationUs += activeChainAdjustmentUs
        awaitingTags = true
      }
      awaitingTags && isOpusTags(packet) -> {
        val tagDurationUs = OpusUtil.getPacketDurationUs(packet)
        activeChainAdjustmentUs += tagDurationUs
        removedDurationUs += tagDurationUs
        awaitingTags = false
        parseOpusTags(packet, activeChainBoundaryUs)?.let(onStreamMetadata)
        onChainHandled(chainCount, activeChainAdjustmentUs)
      }
      else -> {
        super.sampleData(ParsableByteArray(packet), packet.size)
        super.sampleMetadata(timeUs - removedDurationUs, flags, packet.size, 0, cryptoData)
      }
    }
  }

  fun reset() {
    pending.reset()
    removedDurationUs = 0L
    awaitingTags = false
    activeChainAdjustmentUs = 0L
    activeChainBoundaryUs = 0L
    forwardedBytes = 0
  }

  fun setFilteringEnabled(enabled: Boolean) {
    if (filteringEnabled == enabled) return
    pending.reset()
    removedDurationUs = 0L
    awaitingTags = false
    activeChainAdjustmentUs = 0L
    activeChainBoundaryUs = 0L
    forwardedBytes = 0
    filteringEnabled = enabled
  }

  private fun shouldFilter(): Boolean = isOpus && filteringEnabled

  private fun capture(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
    ensureRoom(length)
    val bytes = ByteArray(length)
    val read = input.read(bytes, 0, length)
    if (read < 0) {
      if (allowEndOfInput) return C.RESULT_END_OF_INPUT
      throw EOFException()
    }
    pending.write(bytes, 0, read)
    return read
  }

  private fun captureCandidateOrForward(
    data: ParsableByteArray,
    length: Int,
    sampleDataPart: Int,
  ) {
    require(length >= 0) { "Sample data length must not be negative" }
    val position = data.position
    val bytes = data.data
    val mightBeHeader = length >= 8 &&
      (bytes.startsWith(OPUS_HEAD, position) || bytes.startsWith(OPUS_TAGS, position))
    if (!mightBeHeader) {
      super.sampleData(data, length, sampleDataPart)
      forwardedBytes += length
      return
    }
    ensureRoom(length)
    val candidate = ByteArray(length)
    data.readBytes(candidate, 0, length)
    pending.write(candidate)
  }

  private fun ensureRoom(length: Int) {
    require(length >= 0) { "Sample data length must not be negative" }
    check(pending.size() <= MAX_OPUS_PACKET_BYTES - length) {
      "Opus packet exceeds the ${MAX_OPUS_PACKET_BYTES}-byte filtering limit"
    }
  }

  private fun requireMainData(sampleDataPart: Int) {
    check(sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
      "Chained Opus filtering supports main sample data only"
    }
  }

  private fun isOpusHead(packet: ByteArray): Boolean {
    if (!packet.startsWith(OPUS_HEAD) || packet.size < 19) return false
    val channels = packet[9].toInt() and 0xff
    if (channels == 0) return false
    val mappingFamily = packet[18].toInt() and 0xff
    return if (mappingFamily == 0) {
      channels <= 2 && packet.size == 19
    } else {
      packet.size == 21 + channels
    }
  }

  private fun isOpusTags(packet: ByteArray): Boolean {
    // Only accepted immediately after an exact repeated OpusHead. This matches
    // Media3's own comment-header identification and permits RFC extensions.
    return packet.startsWith(OPUS_TAGS)
  }

  private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
    offset >= 0 && size - offset >= prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

  private companion object {
    const val MAX_OPUS_PACKET_BYTES = 64 * 1024
    const val REQUIRED_PRE_SKIP_SAMPLES = 3_840
    const val PRE_SKIP_DURATION_US = REQUIRED_PRE_SKIP_SAMPLES * 1_000_000L / OpusUtil.SAMPLE_RATE
    val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
  }
}

internal data class OpusStreamMetadata(
  val timeUs: Long,
  val title: String?,
  val artist: String?,
) {
  fun displayTitle(): String? = when {
    title == null -> null
    title.isBlank() -> ""
    !artist.isNullOrBlank() && !title.startsWith(artist, ignoreCase = true) -> "$artist - $title"
    else -> title
  }
}

internal fun parseOpusTags(packet: ByteArray, timeUs: Long): OpusStreamMetadata? {
  if (packet.size < 16 || !packet.startsWithAscii("OpusTags")) return null
  var cursor = 8
  val vendorLength = packet.readLittleEndianUInt(cursor) ?: return null
  cursor += 4
  if (vendorLength > packet.size - cursor) return null
  cursor += vendorLength
  val commentCount = packet.readLittleEndianUInt(cursor) ?: return null
  cursor += 4
  if (commentCount > MAX_OPUS_COMMENTS) return null
  var title: String? = null
  var artist: String? = null
  repeat(commentCount) {
    val length = packet.readLittleEndianUInt(cursor) ?: return null
    cursor += 4
    if (length > packet.size - cursor) return null
    val comment = packet.copyOfRange(cursor, cursor + length).toString(Charsets.UTF_8)
    cursor += length
    val separator = comment.indexOf('=')
    if (separator <= 0) return@repeat
    val value = comment.substring(separator + 1).trim().take(MAX_METADATA_CHARS)
    when (comment.substring(0, separator).uppercase()) {
      "TITLE" -> if (title == null) title = value
      "ARTIST" -> if (artist == null && value.isNotBlank()) artist = value
    }
  }
  if (title == null && artist == null) return null
  return OpusStreamMetadata(timeUs.coerceAtLeast(0), title, artist)
}

private fun ByteArray.startsWithAscii(value: String): Boolean {
  val prefix = value.toByteArray(Charsets.US_ASCII)
  return size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

private fun ByteArray.readLittleEndianUInt(offset: Int): Int? {
  if (offset < 0 || size - offset < 4) return null
  val value = (this[offset].toLong() and 0xff) or
    ((this[offset + 1].toLong() and 0xff) shl 8) or
    ((this[offset + 2].toLong() and 0xff) shl 16) or
    ((this[offset + 3].toLong() and 0xff) shl 24)
  return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private const val MAX_OPUS_COMMENTS = 1_024
private const val MAX_METADATA_CHARS = 512

internal class UnsupportedChainedOpusConfigurationException(
  detail: String = "The Ogg stream changed its Opus decoder configuration between links",
) : IllegalStateException(detail)
