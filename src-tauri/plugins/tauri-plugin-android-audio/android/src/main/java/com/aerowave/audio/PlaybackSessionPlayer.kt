package com.aerowave.audio

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class PlaybackSessionPlayer(
  private val sourcePlayer: Player,
  private val transformMetadata: (MediaMetadata) -> MediaMetadata,
  private val onPlay: () -> Unit,
  private val onPause: () -> Unit,
  private val onStop: () -> Unit,
) : ForwardingSimpleBasePlayer(sourcePlayer) {
  override fun getState(): State {
    // Let Media3 derive every listener event from this state. Kotlin interface
    // delegation skips Player.Listener's Java defaults, which left the session
    // idle and prevented the service from entering the foreground during playback.
    return super.getState().buildUpon()
      .setPlaylist(
        sourcePlayer.currentTimeline,
        sourcePlayer.currentTracks,
        transformMetadata(sourcePlayer.mediaMetadata),
      )
      .build()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    if (playWhenReady) onPlay() else onPause()
    return Futures.immediateVoidFuture()
  }

  override fun handleStop(): ListenableFuture<*> {
    onStop()
    return Futures.immediateVoidFuture()
  }
}
