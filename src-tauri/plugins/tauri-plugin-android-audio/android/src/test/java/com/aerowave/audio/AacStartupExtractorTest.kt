package com.aerowave.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsExtractor
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AacStartupExtractorTest {
  @Test
  fun media3CanLockAnIncorrectFormatFromThePartialFrameBeforeValidAdtsFrames() {
    val output = extract(AdtsExtractor(), joinedMidFrameFixture())
    assertEquals(8000, output.audio.formats.single().sampleRate)
    assertEquals(6, output.audio.formats.single().channelCount)
  }

  @Test
  fun factoryAlignsLiveAacpAcrossShortReads() {
    for (maxRead in listOf(64, 256, 2048)) {
      val extractor = liveAacExtractor()
      val output = extract(extractor, joinedMidFrameFixture(), maxRead)
      assertEquals(44100, output.audio.formats.single().sampleRate)
      assertEquals(2, output.audio.formats.single().channelCount)
      assertEquals(60, output.audio.sampleCount)
    }
  }

  private fun liveAacExtractor(): AacStartAlignedExtractor =
    ChainedOpusExtractorsFactory()
      .createExtractors(
        Uri.parse("http://radio.example:9009/"),
        mapOf("Content-Type" to listOf("audio/aacp")),
      )
      .filterIsInstance<AacStartAlignedExtractor>().single()

  @Test
  fun alreadyAlignedStreamAndFreshConnectionAfterSeekKeepEveryFrame() {
    val extractor = liveAacExtractor()
    val firstInput = input(alignedFixture())
    assertTrue(extractor.sniff(firstInput))
    firstInput.resetPeekPosition()
    val output = RecordingOutput()
    extractor.init(output)
    readAll(extractor, firstInput)
    assertEquals(60, output.audio.sampleCount)

    extractor.seek(0, 0)
    // Reopening the same URL may begin at a different point within a frame.
    // Media3 reuses its extractor after this reconnect rather than sniffing again.
    readAll(extractor, input(joinedMidFrameFixture(prefixLength = 400)))
    extractor.release()
    assertEquals(120, output.audio.sampleCount)
    assertEquals(44100, output.audio.formats.single().sampleRate)
  }

  @Test
  fun factoryKeepsLocalAndOtherMediaSourcesOnTheirExistingPaths() {
    val factory = ChainedOpusExtractorsFactory()
    assertFalse(factory.createExtractors().any { it is AacStartAlignedExtractor })
    assertFalse(factory.createExtractors(
      Uri.parse("content://music/track.aac"),
      mapOf("content-type" to listOf("audio/aacp")),
    ).any { it is AacStartAlignedExtractor })
    assertFalse(factory.createExtractors(
      Uri.parse("https://radio.example/live"),
      mapOf("content-type" to listOf("audio/aac")),
    ).any { it is AacStartAlignedExtractor })
  }

  private fun extract(extractor: Extractor, fixture: ByteArray, maxRead: Int = Int.MAX_VALUE): RecordingOutput {
    val input = input(fixture, maxRead)
    assertTrue(extractor.sniff(input))
    input.resetPeekPosition()
    val output = RecordingOutput()
    extractor.init(output)
    readAll(extractor, input)
    extractor.release()
    return output
  }

  private fun input(fixture: ByteArray, maxRead: Int = Int.MAX_VALUE): DefaultExtractorInput {
    val stream = ByteArrayInputStream(fixture)
    val reader = DataReader { bytes, offset, length -> stream.read(bytes, offset, minOf(length, maxRead)) }
    return DefaultExtractorInput(reader, 0, C.LENGTH_UNSET.toLong())
  }

  private fun readAll(extractor: Extractor, input: ExtractorInput) {
    val seek = PositionHolder()
    repeat(1000) {
      when (extractor.read(input, seek)) {
        Extractor.RESULT_END_OF_INPUT -> return
        Extractor.RESULT_SEEK -> error("Synthetic live stream requested a seek")
      }
    }
    error("Synthetic stream did not finish")
  }

  private fun joinedMidFrameFixture(prefixLength: Int = 600): ByteArray {
    val prefix = ByteArray(prefixLength)
    // The first bytes received from a live endpoint can be the tail of an AAC
    // frame. This ADTS-shaped sequence is inside that tail, not a real frame.
    adtsFrame(objectType = 3, sampleRateIndex = 11, channels = 6, length = 4096)
      .copyInto(prefix, destinationOffset = 100, endIndex = 7)
    return prefix + alignedFixture()
  }

  private fun alignedFixture(): ByteArray = List(60) {
    adtsFrame(objectType = 2, sampleRateIndex = 4, channels = 2, length = 256)
  }.reduce(ByteArray::plus)

  private fun adtsFrame(objectType: Int, sampleRateIndex: Int, channels: Int, length: Int): ByteArray =
    ByteArray(length).apply {
      this[0] = 0xff.toByte()
      this[1] = 0xf1.toByte()
      this[2] = (((objectType - 1) shl 6) or (sampleRateIndex shl 2) or (channels shr 2)).toByte()
      this[3] = (((channels and 3) shl 6) or (length shr 11)).toByte()
      this[4] = (length shr 3).toByte()
      this[5] = (((length and 7) shl 5) or 0x1f).toByte()
      this[6] = 0xfc.toByte()
    }

  private class RecordingOutput : ExtractorOutput {
    val audio = RecordingTrack()
    private val metadata = RecordingTrack()
    override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) audio else metadata
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) = Unit
  }

  private class RecordingTrack : TrackOutput {
    val formats = mutableListOf<Format>()
    var sampleCount = 0
    override fun format(format: Format) { formats += format }
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
      input.read(ByteArray(length), 0, length)
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
      sampleCount++
    }
  }
}
