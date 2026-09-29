package com.aerowave.audio

import android.os.SystemClock
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.PositionHolder
import java.io.EOFException

/** Starts a live ADTS stream at a verified frame boundary after Media3 has sniffed it. */
internal class AacStartAlignedExtractor(
  delegate: Extractor,
  private val onAlignmentEvent: ((String, Int?, Long, String?) -> Unit)? = null,
) : ForwardingExtractor(delegate) {
  private var alignedInput: ExtractorInput? = null

  override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
    // Sniff finds a run of valid frames, but AdtsReader then starts over at byte zero.
    // A live connection can begin inside an earlier frame whose tail resembles a header.
    if (input !== alignedInput) {
      if (input.position == 0L) {
        val startedMs = if (onAlignmentEvent != null) SystemClock.elapsedRealtime() else 0L
        report("start", null, 0, null)
        try {
          val validatedOffset = firstValidatedFrame(input)
          input.resetPeekPosition()
          if (validatedOffset != null && validatedOffset > 0) input.skipFully(validatedOffset)
          report("end", validatedOffset, elapsedSince(startedMs), null)
        } catch (error: Exception) {
          report("error", null, elapsedSince(startedMs), error.javaClass.simpleName)
          throw error
        }
      }
      alignedInput = input
    }
    return super.read(input, seekPosition)
  }

  override fun seek(position: Long, timeUs: Long) {
    super.seek(position, timeUs)
    alignedInput = null
  }

  private fun elapsedSince(startedMs: Long): Long =
    if (onAlignmentEvent == null) 0 else SystemClock.elapsedRealtime() - startedMs

  private fun report(stage: String, offset: Int?, durationMs: Long, error: String?) {
    // A diagnostic callback must never become an alarm playback failure.
    runCatching { onAlignmentEvent?.invoke(stage, offset, durationMs, error) }
  }

  private fun firstValidatedFrame(input: ExtractorInput): Int? {
    for (start in 0 until MAX_START_OFFSET) {
      val first = headerAt(input, start) ?: continue
      var next = start
      var valid = true
      for (frame in 0 until 4) {
        val header = if (next == start) first else headerAt(input, next)
        if (header == null || header.format != first.format) {
          valid = false
          break
        }
        next += header.frameLength
      }
      if (valid && next - start > 188) return start
    }
    return null
  }

  private fun headerAt(input: ExtractorInput, offset: Int): AdtsHeader? {
    if (offset + 7 > MAX_PEEK_BYTES) return null
    val bytes = ByteArray(7)
    try {
      input.resetPeekPosition()
      if (!input.advancePeekPosition(offset, true)) return null
      if (!input.peekFully(bytes, 0, bytes.size, true)) return null
    } catch (_: EOFException) {
      return null
    }
    val b0 = bytes[0].toInt() and 0xff
    val b1 = bytes[1].toInt() and 0xff
    val b2 = bytes[2].toInt() and 0xff
    val b3 = bytes[3].toInt() and 0xff
    val b4 = bytes[4].toInt() and 0xff
    val b5 = bytes[5].toInt() and 0xff
    if (b0 != 0xff || (b1 and 0xf6) != 0xf0) return null
    val sampleRateIndex = (b2 shr 2) and 0x0f
    if (sampleRateIndex > 12) return null
    val frameLength = ((b3 and 3) shl 11) or (b4 shl 3) or (b5 shr 5)
    val minimumLength = if ((b1 and 1) != 0) 7 else 9
    if (frameLength < minimumLength) return null
    val channelConfig = ((b2 and 1) shl 2) or (b3 shr 6)
    val format = ((b1 and 8) shl 12) or ((b2 shr 6) shl 10) or
      (sampleRateIndex shl 4) or channelConfig
    return AdtsHeader(format, frameLength)
  }

  private data class AdtsHeader(val format: Int, val frameLength: Int)

  private companion object {
    const val MAX_START_OFFSET = 8 * 1024
    const val MAX_PEEK_BYTES = 40 * 1024
  }
}
