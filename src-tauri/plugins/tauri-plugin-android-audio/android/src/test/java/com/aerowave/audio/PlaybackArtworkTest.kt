package com.aerowave.audio

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackArtworkTest {
  @Test
  fun acceptsBoundedPngDataUrl() {
    val bytes = pngHeader(256, 128)
    val dataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"

    val artwork = PlaybackArtworkValidator.parse(dataUrl)!!

    assertEquals(dataUrl, artwork.dataUrl)
    assertArrayEquals(bytes, artwork.bytes)
  }

  @Test
  fun nullClearsArtwork() {
    assertNull(PlaybackArtworkValidator.parse(null))
  }

  @Test
  fun rejectsOtherMimeTypesAndInvalidBase64() {
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackArtworkValidator.parse("data:image/jpeg;base64,AAAA")
    }
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackArtworkValidator.parse("data:image/png;base64,not base64")
    }
  }

  @Test
  fun rejectsOversizedDimensionsAndPayloads() {
    val wide = pngHeader(257, 1)
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackArtworkValidator.parse(
        "data:image/png;base64,${Base64.getEncoder().encodeToString(wide)}",
      )
    }
    val oversized = ByteArray(512 * 1024 + 1)
    assertThrows(IllegalArgumentException::class.java) {
      PlaybackArtworkValidator.parse(
        "data:image/png;base64,${Base64.getEncoder().encodeToString(oversized)}",
      )
    }
  }

  private fun pngHeader(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
    write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
    writeBigEndian(13)
    write("IHDR".toByteArray(Charsets.US_ASCII))
    writeBigEndian(width)
    writeBigEndian(height)
  }.toByteArray()

  private fun ByteArrayOutputStream.writeBigEndian(value: Int) {
    write((value ushr 24) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 8) and 0xff)
    write(value and 0xff)
  }
}
