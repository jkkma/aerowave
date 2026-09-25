package com.aerowave.audio

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSessionPlayerTest {
  @Test
  fun mediaControllerSeesRadioLeaveIdleThroughSession() {
    val source = SourcePlayer()
    val sessionPlayer = wrapper(source)
    val context = RuntimeEnvironment.getApplication()
    val session = MediaSession.Builder(context, sessionPlayer).build()
    val controllerFuture = MediaController.Builder(context, session.token).buildAsync()
    try {
      val controller = awaitController(controllerFuture)
      assertEquals(Player.STATE_IDLE, controller.playbackState)

      source.publish("Song", Player.STATE_BUFFERING, playWhenReady = true)
      idleMainLooper()
      assertEquals(1, controller.currentTimeline.windowCount)
      assertEquals(Player.STATE_BUFFERING, controller.playbackState)
      assertTrue(controller.playWhenReady)

      source.publish("Song", Player.STATE_READY, playWhenReady = true)
      idleMainLooper()
      assertEquals(Player.STATE_READY, controller.playbackState)
      assertTrue(controller.isPlaying)
    } finally {
      MediaController.releaseFuture(controllerFuture)
      session.release()
    }
  }

  @Test
  fun forwardsTimelineAndPlaybackEventsWithSessionPlayerIdentity() {
    val source = SourcePlayer()
    val sessionPlayer = wrapper(source)
    val timelineSizes = mutableListOf<Int>()
    val playbackStates = mutableListOf<Int>()
    val playWhenReadyValues = mutableListOf<Boolean>()
    val eventBatches = mutableListOf<Set<Int>>()
    val eventPlayers = mutableListOf<Player>()
    val listener = object : Player.Listener {
      override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        timelineSizes += timeline.windowCount
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        playbackStates += playbackState
      }

      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        playWhenReadyValues += playWhenReady
      }

      override fun onEvents(player: Player, events: Player.Events) {
        eventPlayers += player
        eventBatches += (0 until events.size()).map { events.get(it) }.toSet()
      }
    }
    sessionPlayer.addListener(listener)
    // SimpleBasePlayer captures its initial state lazily on the first getter.
    sessionPlayer.currentTimeline

    source.publish("Private Song", Player.STATE_BUFFERING, playWhenReady = false)
    idleMainLooper()
    assertEquals(1, sessionPlayer.currentTimeline.windowCount)
    assertEquals(Player.STATE_BUFFERING, sessionPlayer.playbackState)
    assertTrue(timelineSizes.contains(1))
    assertTrue(playbackStates.contains(Player.STATE_BUFFERING))
    assertTrue(eventBatches.any { Player.EVENT_TIMELINE_CHANGED in it })
    assertTrue(eventBatches.any { Player.EVENT_PLAYBACK_STATE_CHANGED in it })

    source.publish("Private Song", Player.STATE_READY, playWhenReady = true)
    idleMainLooper()
    assertEquals(Player.STATE_READY, sessionPlayer.playbackState)
    assertTrue(sessionPlayer.playWhenReady)
    assertTrue(sessionPlayer.isPlaying)
    assertTrue(playbackStates.contains(Player.STATE_READY))
    assertTrue(playWhenReadyValues.contains(true))
    assertTrue(eventBatches.any { Player.EVENT_PLAY_WHEN_READY_CHANGED in it })
    assertTrue(eventPlayers.all { it === sessionPlayer })

    val eventsBeforeRemoval = eventBatches.size
    sessionPlayer.removeListener(listener)
    source.publish("Next Song", Player.STATE_READY, playWhenReady = false)
    idleMainLooper()
    assertEquals(eventsBeforeRemoval, eventBatches.size)
  }

  @Test
  fun transformsMetadataForBothGetterAndListenerWithoutChangingSource() {
    val source = SourcePlayer()
    var showSong = false
    val sessionPlayer = wrapper(source) { metadata ->
      if (showSong || metadata.title == null) metadata
      else metadata.buildUpon().setTitle("Station").build()
    }
    val titles = mutableListOf<String?>()
    sessionPlayer.addListener(object : Player.Listener {
      override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        titles += mediaMetadata.title?.toString()
      }
    })
    sessionPlayer.currentTimeline

    source.publish("Private Song", Player.STATE_BUFFERING, playWhenReady = true)
    idleMainLooper()
    assertEquals("Private Song", source.mediaMetadata.title?.toString())
    assertEquals("Station", sessionPlayer.mediaMetadata.title?.toString())
    assertEquals(listOf("Station"), titles)

    showSong = true
    source.publish("Visible Song", Player.STATE_READY, playWhenReady = true)
    idleMainLooper()
    assertEquals("Visible Song", sessionPlayer.mediaMetadata.title?.toString())
    assertEquals(listOf("Station", "Visible Song"), titles)
  }

  @Test
  fun mediaControlsCallServiceActionsAndFollowUnderlyingState() {
    val source = SourcePlayer()
    source.publish("Song", Player.STATE_READY, playWhenReady = false)
    val actions = mutableListOf<String>()
    val sessionPlayer = PlaybackSessionPlayer(
      source,
      { it },
      onPlay = { actions += "play"; source.setPlayWhenReady(true) },
      onPause = { actions += "pause"; source.setPlayWhenReady(false) },
      onStop = { actions += "stop"; source.stop() },
    )

    sessionPlayer.play()
    idleMainLooper()
    assertEquals(listOf("play"), actions)
    assertTrue(source.playWhenReady)
    assertTrue(sessionPlayer.playWhenReady)

    sessionPlayer.pause()
    idleMainLooper()
    assertEquals(listOf("play", "pause"), actions)
    assertFalse(source.playWhenReady)
    assertFalse(sessionPlayer.playWhenReady)

    sessionPlayer.setPlayWhenReady(true)
    idleMainLooper()
    assertEquals(listOf("play", "pause", "play"), actions)
    assertTrue(sessionPlayer.playWhenReady)

    sessionPlayer.stop()
    idleMainLooper()
    assertEquals(listOf("play", "pause", "play", "stop"), actions)
    assertEquals(Player.STATE_IDLE, source.playbackState)
    assertEquals(source.playbackState, sessionPlayer.playbackState)
  }

  private fun wrapper(
    source: SourcePlayer,
    transformMetadata: (MediaMetadata) -> MediaMetadata = { it },
  ) = PlaybackSessionPlayer(source, transformMetadata, {}, {}, {})

  private fun idleMainLooper() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun awaitController(future: ListenableFuture<MediaController>): MediaController {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!future.isDone && System.nanoTime() < deadline) {
      idleMainLooper()
      Thread.sleep(10)
    }
    assertTrue("MediaController did not connect", future.isDone)
    return future.get()
  }

  private class SourcePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
      .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
      .build()

    override fun getState(): State = state

    fun publish(title: String, playbackState: Int, playWhenReady: Boolean) {
      val metadata = MediaMetadata.Builder().setTitle(title).build()
      val item = MediaItem.Builder()
        .setMediaId("radio")
        .setUri("https://example.org/live")
        .setMediaMetadata(metadata)
        .build()
      state = state.buildUpon()
        .setPlaylist(
          listOf(
            MediaItemData.Builder("radio")
              .setMediaItem(item)
              .setMediaMetadata(metadata)
              .build(),
          ),
        )
        .setCurrentMediaItemIndex(0)
        .setPlaybackState(playbackState)
        .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()
      invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
      state = state.buildUpon()
        .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()
      invalidateState()
      return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
      state = state.buildUpon()
        .setPlaybackState(Player.STATE_IDLE)
        .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()
      invalidateState()
      return Futures.immediateVoidFuture()
    }
  }
}
