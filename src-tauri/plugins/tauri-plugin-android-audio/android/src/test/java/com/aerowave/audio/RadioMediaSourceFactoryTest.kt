package com.aerowave.audio

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioMediaSourceFactoryTest {
  @Test fun aRadioManifestCannotOpenLocalChildrenButLocalTracksCanStillOpen() {
    val context = RuntimeEnvironment.getApplication()
    val local = context.cacheDir.resolve("audio.mp3").apply { writeText("fixture content") }
    for (allowsRelay in listOf(true, false)) {
      val factory = RadioMediaSourceFactory.create(context, "Aerowave/test", allowsRelay, DefaultExtractorsFactory())
      val manifest = MediaItem.fromUri("https://radio.test/stream.m3u8")
      for (child in listOf(Uri.fromFile(local), Uri.parse("data:audio/mpeg,audio"))) {
        val dataSource = factory.dataSource(manifest).createDataSource()
        try {
          assertThrows(child.scheme, IOException::class.java) { dataSource.open(DataSpec(child)) }
        } finally {
          dataSource.close()
        }
      }

      val localUri = Uri.fromFile(local)
      val track = factory.dataSource(MediaItem.fromUri(localUri)).createDataSource()
      try {
        assertEquals(15L, track.open(DataSpec(localUri)))
      } finally {
        track.close()
      }
    }
  }
}
