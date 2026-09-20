package com.aerowave.audio

import java.util.Base64

internal data class PlaybackArtwork(
  val dataUrl: String,
  val bytes: ByteArray,
)

internal object PlaybackArtworkValidator {
  private const val PREFIX = "data:image/png;base64,"
  private const val MAX_BYTES = 512 * 1024
  private const val MAX_DIMENSION = 256
  private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
  )

  fun parse(dataUrl: String?): PlaybackArtwork? {
    if (dataUrl == null) return null
    require(dataUrl.startsWith(PREFIX)) { "Artwork must be a PNG data URL" }
    val encoded = dataUrl.substring(PREFIX.length)
    require(encoded.isNotEmpty() && encoded.length <= MAX_ENCODED_BYTES) {
      "Artwork exceeds the $MAX_BYTES-byte limit"
    }
    val bytes = try {
      Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException("Artwork contains invalid base64")
    }
    require(bytes.size <= MAX_BYTES) { "Artwork exceeds the $MAX_BYTES-byte limit" }
    require(bytes.size >= MIN_PNG_BYTES && bytes.startsWith(PNG_SIGNATURE)) {
      "Artwork is not a PNG image"
    }
    require(bytes.readAscii(12, 4) == "IHDR" && bytes.readBigEndianInt(8) == 13) {
      "Artwork has an invalid PNG header"
    }
    val width = bytes.readBigEndianInt(16)
    val height = bytes.readBigEndianInt(20)
    require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
      "Artwork dimensions must be at most ${MAX_DIMENSION}x$MAX_DIMENSION"
    }
    return PlaybackArtwork(dataUrl, bytes)
  }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

  private fun ByteArray.readAscii(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

  private fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
      ((this[offset + 1].toInt() and 0xff) shl 16) or
      ((this[offset + 2].toInt() and 0xff) shl 8) or
      (this[offset + 3].toInt() and 0xff)

  private const val MIN_PNG_BYTES = 24
  private const val MAX_ENCODED_BYTES = ((MAX_BYTES + 2) / 3) * 4
}
