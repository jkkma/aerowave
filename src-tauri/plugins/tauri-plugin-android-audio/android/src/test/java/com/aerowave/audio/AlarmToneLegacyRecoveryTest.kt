package com.aerowave.audio

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27])
class AlarmToneLegacyRecoveryTest {
  private val alarmUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
  private val notificationUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

  /** Controls decoded output independently from accepting a Media3 play request. */
  private class MediaOutput {
    val requested = mutableListOf<Uri>()
    var item: MediaItem? = null
    var volume = 0f
    var repeatMode = Player.REPEAT_MODE_OFF
    var playing = false
    var playWhenReady = false
    var silentUri: Uri? = null
    var throwingUri: Uri? = null
    var stoppedUri: Uri? = null
    var frozenPosition = 0L
    private var sourceStartedAt = 0L
    var ringtoneCalls = 0

    fun position(): Long = if (item?.localConfiguration?.uri == stoppedUri) frozenPosition
      else if (playing) SystemClock.elapsedRealtime() - sourceStartedAt else 0L

    val player: ExoPlayer = Proxy.newProxyInstance(
      ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
    ) { proxy, method, args ->
      when (method.name) {
        "setMediaItem" -> {
          item = args!![0] as MediaItem
          requested += item!!.localConfiguration!!.uri
          sourceStartedAt = SystemClock.elapsedRealtime()
          null
        }
        "getCurrentMediaItem" -> item
        "getMediaItemCount" -> if (item == null) 0 else 1
        "clearMediaItems" -> { item = null; null }
        "getVolume" -> volume
        "setVolume" -> { volume = args!![0] as Float; null }
        "getRepeatMode" -> repeatMode
        "setRepeatMode" -> { repeatMode = args!![0] as Int; null }
        "isPlaying" -> playing
        "getPlayWhenReady" -> playWhenReady
        "getCurrentPosition", "getBufferedPosition" -> position()
        "getCurrentTracks" -> Tracks.EMPTY
        "getPlaybackState" -> if (item == null) Player.STATE_IDLE else Player.STATE_READY
        "stop", "pause", "release" -> { playing = false; playWhenReady = false; null }
        "play" -> {
          val uri = item?.localConfiguration?.uri
          if (uri == throwingUri && uri != null) throw IllegalStateException("Synthetic decoder rejection")
          playWhenReady = true
          playing = uri != null && uri != silentUri
          null
        }
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.get(0)
        "toString" -> "Legacy tone output"
        else -> when (method.returnType) {
          java.lang.Boolean.TYPE -> false
          java.lang.Integer.TYPE -> 0
          java.lang.Long.TYPE -> 0L
          java.lang.Float.TYPE -> 0f
          else -> null
        }
      }
    } as ExoPlayer
  }

  private fun start(output: MediaOutput):
    Pair<org.robolectric.android.controller.ServiceController<AlarmPlaybackService>, AlarmPlaybackService> {
    val context = RuntimeEnvironment.getApplication()
    val active = RingingRecord(
      alarm = NativeAlarm(
        id = "legacy-recovery", label = "Legacy recovery", hour = 7, minute = 0,
        days = emptyList(), enabled = true, source = NativeAlarmSource.Station("missing"),
        volume = 0.5f, fadeSecs = 0, snoozeMins = 10, autoStopMins = 0, autoSnoozes = 0,
      ),
      occurrenceId = "scheduled:legacy-recovery", trigger = "scheduled",
      startedAtMs = System.currentTimeMillis(), startedElapsedMs = SystemClock.elapsedRealtime(),
    )
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    val playerField = AlarmPlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
    (playerField.get(service) as ExoPlayer).let { it.removeListener(service); it.release() }
    playerField.set(service, output.player)
    service.systemToneFactory = { _, _ -> output.ringtoneCalls++; null }
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId), 0, 1,
    )
    return controller to service
  }

  private fun ring(context: Context = RuntimeEnvironment.getApplication()) = AlarmStateStore.snapshot(context).ringing

  private fun error(service: AlarmPlaybackService) {
    service.onPlayerError(PlaybackException("Synthetic decoding failure", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
  }

  private fun idleFor(seconds: Long) {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
  }

  @Test fun decoderFailureAdvancesToNotificationAndPreservesRepeatVolumeAndMedia3() {
    val output = MediaOutput()
    val (controller, service) = start(output)
    try {
      assertEquals(listOf(alarmUri), output.requested)
      error(service)
      idleFor(3)
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertEquals(notificationUri.toString(), ring()?.sourceUri)
      assertTrue(ring()?.note?.contains("notification sound") == true)
      assertTrue(output.playing)
      assertTrue(output.position() > 0L)
      assertEquals(Player.REPEAT_MODE_ONE, output.repeatMode)
      assertEquals(0.5f, output.volume, 0.001f)
      assertEquals(0, output.ringtoneCalls)
    } finally { controller.destroy() }
  }

  @Test fun decoderThatNeverStartsAdvancesToNotificationAtTheLocalStartupDeadline() {
    val output = MediaOutput().apply { silentUri = alarmUri }
    val (controller, _) = start(output)
    try {
      idleFor(7)
      assertEquals(listOf(alarmUri), output.requested)
      idleFor(2)
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertTrue(output.playing)
      assertEquals(notificationUri.toString(), ring()?.sourceUri)
      assertEquals(0, output.ringtoneCalls)
    } finally { controller.destroy() }
  }

  @Test fun decoderThatStopsAdvancingTriesTheRemainingNotificationCandidate() {
    val output = MediaOutput()
    val (controller, _) = start(output)
    try {
      idleFor(3)
      output.frozenPosition = output.position()
      output.stoppedUri = alarmUri
      assertTrue(output.frozenPosition > 0)
      idleFor(13)
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertTrue(output.playing)
      assertEquals(notificationUri.toString(), ring()?.sourceUri)
    } finally { controller.destroy() }
  }

  @Test fun unexpectedMediaEndAdvancesToTheNotificationCandidate() {
    val output = MediaOutput()
    val (controller, service) = start(output)
    try {
      service.onPlaybackStateChanged(Player.STATE_ENDED)
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertTrue(output.playing)
      assertEquals(notificationUri.toString(), ring()?.sourceUri)
    } finally { controller.destroy() }
  }

  @Test fun synchronousLegacyPlayFailureTriesNotificationBeforeReturning() {
    val output = MediaOutput().apply { throwingUri = alarmUri }
    val (controller, _) = start(output)
    try {
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertTrue(output.playing)
      assertEquals(notificationUri.toString(), ring()?.sourceUri)
    } finally { controller.destroy() }
  }

  @Test fun exhaustionClearsTheLegacySourceAndDoesNotRestartOnFocusGainOrOldErrors() {
    val output = MediaOutput()
    val (controller, service) = start(output)
    try {
      error(service)
      error(service)
      val terminal = ring()
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertNull(terminal?.sourceUri)
      assertTrue(terminal?.note?.contains("system alarm sound could not play") == true)
      assertFalse(output.playing)
      assertNull(output.item)
      val listener = AlarmPlaybackService::class.java.getDeclaredField("focusListener")
        .apply { isAccessible = true }.get(service) as AudioManager.OnAudioFocusChangeListener
      listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
      idleFor(20)
      error(service)
      assertEquals(listOf(alarmUri, notificationUri), output.requested)
      assertEquals(terminal, ring())
      assertFalse(output.playing)
      assertEquals(0, output.ringtoneCalls)
    } finally { controller.destroy() }
  }
}
