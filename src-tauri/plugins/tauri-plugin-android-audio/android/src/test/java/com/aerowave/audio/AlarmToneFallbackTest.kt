package com.aerowave.audio

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
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
@Config(sdk = [33])
class AlarmToneFallbackTest {
  private class FakeTone(
    private val neverReportsPlaying: Boolean = false,
    private val throwsOnPlay: Boolean = false,
  ) : SystemAlarmTone {
    var playCalls = 0
    var stopCalls = 0
    var playing = false
    val volumes = mutableListOf<Float>()

    override fun play() {
      playCalls++
      if (throwsOnPlay) throw IllegalStateException("tone unavailable")
      playing = !neverReportsPlaying
    }

    override fun stop() {
      stopCalls++
      playing = false
    }

    override fun isPlaying() = playing

    override fun setVolume(volume: Float) {
      volumes += volume
    }
  }

  private fun ring(
    sourceKind: String = "tone", sourceUri: String? = null, fadeSecs: Int = 0,
  ) = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Station("missing"),
      volume = 0.6f, fadeSecs = fadeSecs, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = "scheduled:tone", trigger = "scheduled",
    startedAtMs = System.currentTimeMillis(), startedElapsedMs = SystemClock.elapsedRealtime(),
    sourceKind = sourceKind, sourceUri = sourceUri,
  )

  private fun start(
    context: Context, active: RingingRecord,
    factory: (Context, Uri) -> SystemAlarmTone?,
  ): Pair<org.robolectric.android.controller.ServiceController<AlarmPlaybackService>, AlarmPlaybackService> {
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.systemToneFactory = factory
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId),
      0, 1,
    )
    return controller to service
  }

  private fun focus(service: AlarmPlaybackService, change: Int) {
    val field = AlarmPlaybackService::class.java.getDeclaredField("focusListener")
    field.isAccessible = true
    val listener = field.get(service) as AudioManager.OnAudioFocusChangeListener
    listener.onAudioFocusChange(change)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
  }

  @Test fun missingStationUsesMediaRingtoneAndDismissStopsIt() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring()
    val tone = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, service) = start(context, active) { _, uri ->
      requested += uri
      tone
    }
    assertEquals(listOf(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)), requested)
    assertEquals(1, tone.playCalls)
    assertTrue(tone.playing)
    assertEquals(0.6f, tone.volumes.last(), 0.001f)
    val ringing = AlarmStateStore.snapshot(context).ringing
    assertEquals("tone", ringing?.sourceKind)
    assertEquals(requested.single().toString(), ringing?.sourceUri)
    // A fresh claim has default sourceKind=tone but must still resolve its station.
    assertTrue(ringing?.note?.contains("selected station is no longer saved") == true)

    AlarmPlaybackService.dismissIfMatching(context, active.occurrenceId)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertNull(AlarmStateStore.snapshot(context).ringing)
    assertFalse(tone.playing)
    assertTrue(tone.stopCalls > 0)
    controller.destroy()
  }

  @Test fun focusDuckPauseAndGainApplyToRingtoneWithoutChangingMediaVolume() {
    val context = RuntimeEnvironment.getApplication()
    val mediaVolume = context.getSystemService(AudioManager::class.java)
      .getStreamVolume(AudioManager.STREAM_MUSIC)
    val tone = FakeTone()
    val (controller, service) = start(context, ring()) { _, _ -> tone }

    focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
    assertEquals(0.12f, tone.volumes.last(), 0.001f)
    focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    assertFalse(tone.playing)
    val callsBeforeGain = tone.playCalls
    focus(service, AudioManager.AUDIOFOCUS_GAIN)
    assertEquals(callsBeforeGain + 1, tone.playCalls)
    assertTrue(tone.playing)
    assertEquals(0.6f, tone.volumes.last(), 0.001f)
    assertEquals(mediaVolume, context.getSystemService(AudioManager::class.java)
      .getStreamVolume(AudioManager.STREAM_MUSIC))
    controller.destroy()
  }

  @Test fun ringtoneFadeChangesOnlyItsOwnVolume() {
    val context = RuntimeEnvironment.getApplication()
    val mediaVolume = context.getSystemService(AudioManager::class.java)
      .getStreamVolume(AudioManager.STREAM_MUSIC)
    val tone = FakeTone()
    val (controller, _) = start(context, ring(fadeSecs = 10)) { _, _ -> tone }
    assertTrue(tone.volumes.last() <= 0.02f)
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    assertTrue(tone.volumes.last() in 0.25f..0.35f)
    assertEquals(mediaVolume, context.getSystemService(AudioManager::class.java)
      .getStreamVolume(AudioManager.STREAM_MUSIC))
    controller.destroy()
  }

  @Test fun restoredToneAndNotificationFallbackUseRingtone() {
    val context = RuntimeEnvironment.getApplication()
    val active = ring(
      sourceKind = "tone",
      sourceUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString(),
    )
    val tone = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, _) = start(context, active) { _, uri ->
      requested += uri
      if (requested.size == 1) null else tone
    }
    assertEquals(
      listOf(
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
      ),
      requested,
    )
    assertEquals(1, tone.playCalls)
    val ringing = AlarmStateStore.snapshot(context).ringing
    assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString(),
      ringing?.sourceUri)
    assertTrue(ringing?.note?.contains("notification sound") == true)
    controller.destroy()
  }

  @Test fun failedRingtoneKeepsRingVisibleWithAPlaybackError() {
    val context = RuntimeEnvironment.getApplication()
    val tone = FakeTone(throwsOnPlay = true)
    val (controller, service) = start(context, ring()) { _, _ -> tone }
    val ringing = AlarmStateStore.snapshot(context).ringing
    assertEquals("tone", ringing?.sourceKind)
    assertNull(ringing?.sourceUri)
    assertTrue(ringing?.note?.contains("system alarm sound could not play") == true)
    assertTrue(tone.stopCalls > 0)
    focus(service, AudioManager.AUDIOFOCUS_GAIN)
    assertEquals(ringing?.note, AlarmStateStore.snapshot(context).ringing?.note)
    controller.destroy()
  }

  @Test fun ringtoneThatNeverPlaysFailsItsOwnWatchdog() {
    val context = RuntimeEnvironment.getApplication()
    val tone = FakeTone(neverReportsPlaying = true)
    val (controller, _) = start(context, ring()) { _, _ -> tone }
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    assertTrue(tone.playCalls >= 2)
    val ringing = AlarmStateStore.snapshot(context).ringing
    assertEquals("tone", ringing?.sourceKind)
    assertTrue(ringing?.note?.contains("system alarm sound stopped playing") == true)
    assertNull(ringing?.sourceUri)
    controller.destroy()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class AlarmToneLegacyTest {
  @Test fun api27KeepsMedia3TonePathAndNeverCallsRingtoneFactory() {
    val context = RuntimeEnvironment.getApplication()
    val active = RingingRecord(
      alarm = NativeAlarm(
        id = "legacy", label = "Legacy", hour = 7, minute = 0,
        days = emptyList(), enabled = true,
        source = NativeAlarmSource.Station("missing"),
        volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
        autoStopMins = 0, autoSnoozes = 0,
      ),
      occurrenceId = "scheduled:legacy", trigger = "scheduled",
      startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000L,
    )
    AlarmStateStore.update(context) { PersistedAlarmState(ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    var ringtoneFactoryCalls = 0
    service.systemToneFactory = { _, _ ->
      ringtoneFactoryCalls++
      null
    }
    service.onStartCommand(
      AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId),
      0, 1,
    )
    assertEquals(0, ringtoneFactoryCalls)
    assertEquals("tone", AlarmStateStore.snapshot(context).ringing?.sourceKind)
    assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString(),
      AlarmStateStore.snapshot(context).ringing?.sourceUri)
    controller.destroy()
  }
}
