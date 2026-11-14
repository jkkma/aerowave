package com.aerowave.audio

import android.content.Intent
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackReconnectTest {
  private val root = "https://radio.test/listen"
  private val oldManifest = "https://radio.test/old.m3u8"
  private val freshManifest = "https://radio.test/fresh.m3u8"

  private fun fixture() = Robolectric.buildService(PlaybackService::class.java).create().also {
    // Inject failures at the service listener boundary below. Media3's
    // background loader would otherwise cause competing real network errors.
    val service = it.get()
    val player = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
      .get(service) as ExoPlayer
    player.removeListener(service)
  }

  private fun start(service: PlaybackService, generation: Long, manifest: String) {
    val request = PlayRequest(manifest, manifest, "Radio", null, 0.8f, generation, true, stationUrl = root)
    service.onStartCommand(PlaybackService.intentFor(service, PlaybackService.ACTION_PLAY, request), 0, 1)
  }

  private fun fail(service: PlaybackService) = service.onPlayerError(
    PlaybackException("fixture failure", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
  )

  @Test fun laterRetriesResolveTheOriginalAndKeepThePlaybackIdentity() {
    val controller = fixture()
    val service = controller.get()
    val looper = shadowOf(Looper.getMainLooper())
    val tasks = ArrayDeque<Runnable>()
    service.stationLookupExecutor = Executor { tasks.add(it) }
    service.stationResolver = { station, _ ->
      assertEquals(root, station)
      ResolvedAlarmStream(freshManifest, true)
    }
    try {
      start(service, 8L, oldManifest)
      fail(service)
      looper.idleFor(Duration.ofSeconds(1))
      assertEquals(0, tasks.size)
      fail(service)
      looper.idleFor(Duration.ofSeconds(2))
      tasks.removeFirst().run()
      looper.idle()
      val state = AudioStateStore.snapshot(service)
      assertEquals(freshManifest, state.playableUrl)
      assertEquals(oldManifest, state.sourceUrl)
      assertEquals(8L, state.generation)
    } finally {
      controller.destroy()
    }
  }

  @Test fun pauseAndReplacementRetireRefreshRepliesAlreadyQueuedOnTheMainThread() {
    val controller = fixture()
    val service = controller.get()
    val looper = shadowOf(Looper.getMainLooper())
    val tasks = ArrayDeque<Runnable>()
    service.stationLookupExecutor = Executor { tasks.add(it) }
    service.stationResolver = { _, _ -> ResolvedAlarmStream(freshManifest, true) }
    try {
      for (pause in listOf(true, false)) {
        start(service, 8L, oldManifest)
        fail(service)
        looper.idleFor(Duration.ofSeconds(1))
        fail(service)
        looper.idleFor(Duration.ofSeconds(2))
        tasks.removeFirst().run()
        if (pause) {
          val intent = Intent(service, PlaybackService::class.java).setAction(PlaybackService.ACTION_PAUSE)
          service.onStartCommand(intent, 0, 2)
        } else {
          start(service, 9L, "https://radio.test/replacement.m3u8")
        }
        looper.idle()
        val state = AudioStateStore.snapshot(service)
        assertEquals(if (pause) oldManifest else "https://radio.test/replacement.m3u8", state.playableUrl)
        if (pause) assertEquals(STATUS_PAUSED, state.status)
      }
      assertNull(tasks.removeFirstOrNull())
    } finally {
      controller.destroy()
    }
  }

  @Test fun changedFormatConsumesTheRetryBudgetWithoutPlayingTheOrdinaryUrlDirectly() {
    val controller = fixture()
    val service = controller.get()
    val looper = shadowOf(Looper.getMainLooper())
    var lookups = 0
    service.stationLookupExecutor = Executor { it.run() }
    service.stationResolver = { _, _ ->
      lookups++
      ResolvedAlarmStream("https://radio.test/ordinary.mp3", false)
    }
    try {
      start(service, 8L, oldManifest)
      fail(service)
      looper.idleFor(Duration.ofSeconds(1))
      fail(service)
      looper.idleFor(Duration.ofSeconds(14))
      val state = AudioStateStore.snapshot(service)
      assertEquals(3, lookups)
      assertEquals(oldManifest, state.playableUrl)
      assertEquals(STATUS_ERROR, state.status)
      assertEquals("The station no longer serves HLS", state.error)
    } finally {
      controller.destroy()
    }
  }
}
