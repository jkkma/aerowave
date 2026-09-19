package com.aerowave.audio

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.OpusUtil
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ogg.OggExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChainedOpusExtractorsFactoryTest {
  @Test
  fun removesSecondLinkHeadersRefreshesFormatAndPreparesContinuousDecodedTimeline() {
    val delegate = RecordingTrackOutput()
    val handled = mutableListOf<Pair<Int, Long>>()
    val output = ChainedOpusTrackOutput(delegate, onChainHandled = { chain, adjustment ->
      handled += chain to adjustment
    })
    output.format(opusFormat(OPUS_HEAD))
    val firstAudio = audioPacket(0x11)
    emit(output, firstAudio, 0)

    val boundaryUs = 100_000L
    val headDurationUs = OpusUtil.getPacketDurationUs(OPUS_HEAD)
    val tagsDurationUs = OpusUtil.getPacketDurationUs(OPUS_TAGS)
    emit(output, OPUS_HEAD, boundaryUs)
    emit(output, OPUS_TAGS, boundaryUs + headDurationUs)
    val secondLinkPackets = (1..5).map(::audioPacket)
    secondLinkPackets.forEachIndexed { index, packet ->
      emit(
        output,
        packet,
        boundaryUs + headDurationUs + tagsDurationUs + index * PACKET_DURATION_US,
        usePartOverload = index == 0,
      )
    }

    assertEquals(6, delegate.samples.size)
    assertArrayEquals(firstAudio, delegate.samples[0].bytes)
    secondLinkPackets.forEachIndexed { index, packet ->
      assertArrayEquals(packet, delegate.samples[index + 1].bytes)
    }
    // The decoder reset discards four pre-roll packets. Their encoded PTS
    // intentionally overlap the old link; the first audible packet lands at
    // the old link's boundary without a growing timestamp gap.
    assertEquals(
      listOf(-80_000L, -60_000L, -40_000L, -20_000L, 0L).map { boundaryUs + it },
      delegate.samples.drop(1).map { it.timeUs },
    )
    assertTrue(delegate.samples.drop(1).zipWithNext().all { (a, b) -> b.timeUs >= a.timeUs })
    assertEquals(boundaryUs, delegate.samples.last().timeUs)
    assertEquals(2, delegate.formats.size)
    assertNotEquals(delegate.formats[0].id, delegate.formats[1].id)
    assertTrue(delegate.formats[0].initializationDataEquals(delegate.formats[1]))
    assertEquals(
      listOf(1 to headDurationUs + tagsDurationUs + PRE_SKIP_DURATION_US),
      handled,
    )
  }

  @Test
  fun realOggExtractorEmitsSecondLinkHeadersAndWrapperRemovesThem() {
    val fixture = chainedOggFixture()
    val unwrapped = extract(OggExtractor(), fixture)
    assertTrue(unwrapped.track.samples.any { it.bytes.contentEquals(OPUS_HEAD) })
    assertTrue(unwrapped.track.samples.any { it.bytes.contentEquals(OPUS_TAGS) })

    val factory = ChainedOpusExtractorsFactory(ExtractorsFactory { arrayOf(OggExtractor()) })
    val filtered = extract(factory.createExtractors().single(), fixture)
    val expectedAudio = listOf(audioPacket(0x10)) + (1..5).map(::audioPacket)

    assertEquals(expectedAudio.size, filtered.track.samples.size)
    expectedAudio.forEachIndexed { index, packet ->
      assertArrayEquals(packet, filtered.track.samples[index].bytes)
    }
    assertTrue(filtered.track.samples.none { it.bytes.contentEquals(OPUS_HEAD) })
    assertTrue(filtered.track.samples.none { it.bytes.contentEquals(OPUS_TAGS) })
    assertEquals(2, filtered.track.formats.size)
    assertNotEquals(filtered.track.formats[0].id, filtered.track.formats[1].id)
    assertTrue(filtered.track.formats[0].initializationDataEquals(filtered.track.formats[1]))
    val secondLinkTimes = filtered.track.samples.drop(1).map { it.timeUs }
    assertEquals(listOf(-60_000L, -40_000L, -20_000L, 0L, 20_000L), secondLinkTimes)
    assertTrue(secondLinkTimes.zipWithNext().all { (a, b) -> b >= a })
  }

  @Test
  fun multipleLinkAdjustmentsAccumulateWithoutGrowingAudibleGap() {
    val delegate = RecordingTrackOutput()
    val handled = mutableListOf<Pair<Int, Long>>()
    val output = ChainedOpusTrackOutput(delegate, onChainHandled = { chain, adjustment ->
      handled += chain to adjustment
    })
    output.format(opusFormat(OPUS_HEAD))
    emit(output, audioPacket(0), 0)
    var rawTimeUs = PACKET_DURATION_US

    repeat(2) { chain ->
      emit(output, OPUS_HEAD, rawTimeUs)
      rawTimeUs += OpusUtil.getPacketDurationUs(OPUS_HEAD)
      emit(output, OPUS_TAGS, rawTimeUs)
      rawTimeUs += OpusUtil.getPacketDurationUs(OPUS_TAGS)
      repeat(5) { packet ->
        emit(output, audioPacket((chain + 1) * 10 + packet), rawTimeUs)
        rawTimeUs += PACKET_DURATION_US
      }
    }

    assertEquals(3, delegate.formats.size)
    assertEquals(listOf(20_000L, 40_000L), listOf(delegate.samples[5].timeUs, delegate.samples[10].timeUs))
    assertEquals(
      listOf(1, 2).map { it to 2_000_000L },
      handled,
    )
  }

  @Test
  fun nonOpusAudioPassesThroughUnchanged() {
    val delegate = RecordingTrackOutput()
    val output = ChainedOpusTrackOutput(delegate)
    output.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_VORBIS).build())

    emit(output, OPUS_HEAD, 555_000)

    assertEquals(1, delegate.formats.size)
    assertEquals(1, delegate.samples.size)
    assertArrayEquals(OPUS_HEAD, delegate.samples.single().bytes)
    assertEquals(555_000L, delegate.samples.single().timeUs)
  }

  @Test
  fun seekableOpusPassesHeadersAndTimestampsThroughUnchanged() {
    val delegate = RecordingTrackOutput()
    val output = ChainedOpusTrackOutput(delegate, filteringEnabled = false)
    output.format(opusFormat(OPUS_HEAD))

    emit(output, OPUS_HEAD, 20_000)
    emit(output, OPUS_TAGS, 980_000)

    assertEquals(1, delegate.formats.size)
    assertEquals(listOf(20_000L, 980_000L), delegate.samples.map { it.timeUs })
    assertArrayEquals(OPUS_HEAD, delegate.samples[0].bytes)
    assertArrayEquals(OPUS_TAGS, delegate.samples[1].bytes)
  }

  @Test
  fun identicalHeaderWithUnsupportedPreSkipFailsClearly() {
    val unsupportedHead = OPUS_HEAD.clone().also {
      it[10] = 0x38
      it[11] = 0x01
    }
    val output = ChainedOpusTrackOutput(RecordingTrackOutput())
    output.format(opusFormat(unsupportedHead))
    output.sampleData(ParsableByteArray(unsupportedHead), unsupportedHead.size)

    val error = assertThrows(UnsupportedChainedOpusConfigurationException::class.java) {
      output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, unsupportedHead.size, 0, null)
    }

    assertTrue(error.message.orEmpty().contains("unsupported pre-skip 312"))
  }

  @Test
  fun audioThatOnlyStartsWithAHeaderPrefixIsNotFiltered() {
    val delegate = RecordingTrackOutput()
    val output = ChainedOpusTrackOutput(delegate)
    output.format(opusFormat(OPUS_HEAD))
    val audio = "OpusTags-not-a-comment-packet".toByteArray()

    emit(output, audio, 123_000)

    assertEquals(1, delegate.samples.size)
    assertArrayEquals(audio, delegate.samples.single().bytes)
    assertEquals(123_000L, delegate.samples.single().timeUs)
  }

  @Test
  fun seekResetClearsTimestampAdjustment() {
    val delegate = RecordingTrackOutput()
    val output = ChainedOpusTrackOutput(delegate)
    output.format(opusFormat(OPUS_HEAD))
    emit(output, OPUS_HEAD, 1_000_000)
    emit(output, OPUS_TAGS, 1_000_000 + OpusUtil.getPacketDurationUs(OPUS_HEAD))

    output.reset()
    val audio = byteArrayOf(0x98.toByte(), 0x55)
    emit(output, audio, 7_000_000)

    assertEquals(7_000_000L, delegate.samples.single().timeUs)
    assertArrayEquals(audio, delegate.samples.single().bytes)
  }

  @Test
  fun changedDecoderConfigurationFailsBeforeAudioIsForwarded() {
    val delegate = RecordingTrackOutput()
    val output = ChainedOpusTrackOutput(delegate)
    output.format(opusFormat(OPUS_HEAD))
    val changedHead = OPUS_HEAD.clone().also { it[9] = 1 }

    output.sampleData(ParsableByteArray(changedHead), changedHead.size)
    val error = assertThrows(UnsupportedChainedOpusConfigurationException::class.java) {
      output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, changedHead.size, 0, null)
    }

    assertTrue(error.message.orEmpty().contains("decoder configuration"))
    assertTrue(delegate.samples.isEmpty())
  }

  @Test
  fun dataReaderOverloadForwardsOneCopyOfRealAudio() {
    val delegate = RecordingTrackOutput()
    val output = ChainedOpusTrackOutput(delegate)
    output.format(opusFormat(OPUS_HEAD))
    val audio = byteArrayOf(0x98.toByte(), 0x66, 0x77)
    var consumed = false
    val reader = DataReader { target, offset, length ->
      if (consumed) C.RESULT_END_OF_INPUT else {
        consumed = true
        audio.copyInto(target, offset, 0, minOf(length, audio.size))
        minOf(length, audio.size)
      }
    }

    assertEquals(audio.size, output.sampleData(reader, audio.size, false))
    output.sampleMetadata(42, C.BUFFER_FLAG_KEY_FRAME, audio.size, 0, null)

    assertEquals(1, delegate.samples.size)
    assertArrayEquals(audio, delegate.samples.single().bytes)
  }

  @Test
  fun rejectsNonzeroMetadataOffsetInsteadOfMisframingAPacket() {
    val output = ChainedOpusTrackOutput(RecordingTrackOutput())
    output.format(opusFormat(OPUS_HEAD))
    val audio = byteArrayOf(0x98.toByte(), 0x01)
    output.sampleData(ParsableByteArray(audio), audio.size)

    val error = assertThrows(IllegalStateException::class.java) {
      output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, audio.size, 1, null)
    }

    assertTrue(error.message.orEmpty().contains("zero sample metadata offset"))
  }

  private fun emit(
    output: ChainedOpusTrackOutput,
    bytes: ByteArray,
    timeUs: Long,
    usePartOverload: Boolean = false,
  ) {
    val data = ParsableByteArray(bytes)
    if (usePartOverload) {
      output.sampleData(data, bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
    } else {
      output.sampleData(data, bytes.size)
    }
    output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
  }

  private fun opusFormat(head: ByteArray): Format = Format.Builder()
    .setId("initial-opus")
    .setSampleMimeType(MimeTypes.AUDIO_OPUS)
    .setChannelCount(2)
    .setSampleRate(OpusUtil.SAMPLE_RATE)
    .setInitializationData(OpusUtil.buildInitializationData(head))
    .build()

  private fun extract(extractor: Extractor, fixture: ByteArray): RecordingExtractorOutput {
    val stream = ByteArrayInputStream(fixture)
    val reader = DataReader(stream::read)
    val input = DefaultExtractorInput(reader, 0, C.LENGTH_UNSET.toLong())
    assertTrue(extractor.sniff(input))
    input.resetPeekPosition()
    val output = RecordingExtractorOutput()
    extractor.init(output)
    val seek = PositionHolder()
    while (true) {
      val result = extractor.read(input, seek)
      if (result == Extractor.RESULT_END_OF_INPUT) break
      check(result != Extractor.RESULT_SEEK) { "Synthetic live fixture unexpectedly requested a seek" }
    }
    extractor.release()
    return output
  }

  private fun chainedOggFixture(): ByteArray {
    val output = ByteArrayOutputStream()
    fun page(type: Int, granule: Long, serial: Int, sequence: Int, packets: List<ByteArray>) {
      packets.forEach { require(it.size < 255) }
      output.write("OggS".toByteArray())
      output.write(0)
      output.write(type)
      output.writeLittleEndian(granule, 8)
      output.writeLittleEndian(serial.toLong(), 4)
      output.writeLittleEndian(sequence.toLong(), 4)
      output.writeLittleEndian(0, 4) // OggExtractor does not validate the page CRC.
      output.write(packets.size)
      packets.forEach { output.write(it.size) }
      packets.forEach(output::write)
    }

    page(0x02, 0, 1, 0, listOf(OPUS_HEAD))
    page(0, 0, 1, 1, listOf(OPUS_TAGS))
    page(0, 4_800, 1, 2, listOf(audioPacket(0x10)))
    page(0x04, 4_800, 1, 3, emptyList())
    page(0x02, 0, 2, 0, listOf(OPUS_HEAD))
    page(0, 0, 2, 1, listOf(OPUS_TAGS))
    page(0, 8_640, 2, 2, (1..5).map(::audioPacket))
    page(0x04, 8_640, 2, 3, emptyList())
    return output.toByteArray()
  }

  private fun ByteArrayOutputStream.writeLittleEndian(value: Long, byteCount: Int) {
    repeat(byteCount) { shift -> write((value ushr (shift * 8)).toInt() and 0xff) }
  }

  private fun audioPacket(marker: Int): ByteArray = byteArrayOf(0x98.toByte(), marker.toByte())

  private data class CapturedSample(val timeUs: Long, val bytes: ByteArray)

  private class RecordingTrackOutput : TrackOutput {
    val formats = mutableListOf<Format>()
    val samples = mutableListOf<CapturedSample>()
    private val pending = ByteArrayOutputStream()

    override fun format(format: Format) {
      formats += format
    }

    override fun sampleData(
      input: DataReader,
      length: Int,
      allowEndOfInput: Boolean,
      sampleDataPart: Int,
    ): Int {
      val bytes = ByteArray(length)
      val read = input.read(bytes, 0, length)
      if (read >= 0) pending.write(bytes, 0, read)
      return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
      val bytes = ByteArray(length)
      data.readBytes(bytes, 0, length)
      pending.write(bytes)
    }

    override fun sampleMetadata(
      timeUs: Long,
      flags: Int,
      size: Int,
      offset: Int,
      cryptoData: TrackOutput.CryptoData?,
    ) {
      val all = pending.toByteArray()
      samples += CapturedSample(timeUs, all.copyOfRange(all.size - offset - size, all.size - offset))
      pending.reset()
      if (offset > 0) pending.write(all, all.size - offset, offset)
    }
  }

  private class RecordingExtractorOutput : ExtractorOutput {
    val track = RecordingTrackOutput()
    override fun track(id: Int, type: Int): TrackOutput = track
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) {
      assertTrue(!seekMap.isSeekable)
    }
  }

  private companion object {
    val OPUS_HEAD = byteArrayOf(
      0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64,
      0x01, 0x02, 0x00, 0x0f, 0x80.toByte(), 0xbb.toByte(), 0x00, 0x00,
      0x00, 0x00, 0x00,
    )
    val OPUS_TAGS = "OpusTags".toByteArray() + byteArrayOf(
      0x04, 0x00, 0x00, 0x00,
    ) + "test".toByteArray() + byteArrayOf(
      0x00, 0x00, 0x00, 0x00,
    )
    const val PRE_SKIP_DURATION_US = 80_000L
    const val PACKET_DURATION_US = 20_000L
  }
}
