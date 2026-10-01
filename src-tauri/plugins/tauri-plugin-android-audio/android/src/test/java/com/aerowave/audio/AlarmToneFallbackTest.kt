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
    var neverReportsPlaying: Boolean = false,
    var throwsOnPlay: Boolean = false,
  ) : SystemAlarmTone {
    var playCalls = 0
    var stopCalls = 0
    var playing = false
    val volumes = mutableListOf<Float>()
    var volumeAtFirstPlay: Float? = null
    var throwsOnVolume = false

    override fun play() {
      if (playCalls == 0) volumeAtFirstPlay = volumes.lastOrNull()
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
      if (throwsOnVolume) throw IllegalStateException("tone volume unavailable")
      volumes += volume
    }
  }

  private fun ring(
    sourceKind: String = "tone", sourceUri: String? = null, fadeSecs: Int = 0,
    startedAgoMs: Long = 0,
  ) = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Station("missing"),
      volume = 0.6f, fadeSecs = fadeSecs, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = "scheduled:tone", trigger = "scheduled",
    startedAtMs = System.currentTimeMillis(),
    startedElapsedMs = SystemClock.elapsedRealtime() - startedAgoMs,
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

  private fun watchdog(service: AlarmPlaybackService): Runnable =
    AlarmPlaybackService::class.java.getDeclaredField("toneWatchdog")
      .apply { isAccessible = true }.get(service) as Runnable

  private fun idleFor(seconds: Long) {
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
  }

  private val alarmUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
  private val notificationUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

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

  @Test fun delayedFallbackStartsQuietlyDespiteOldRingClock() {
    val context = RuntimeEnvironment.getApplication()
    val tone = FakeTone()
    val (controller, _) = start(context, ring(fadeSecs = 20, startedAgoMs = 15_000)) { _, _ -> tone }
    assertEquals(0.012f, tone.volumeAtFirstPlay ?: 1f, 0.001f)
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    assertEquals(0.15f, tone.volumes.last(), 0.015f)
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

  @Test fun silentDefaultToneAdvancesToNotificationWithoutSpendingTheFade() {
    val context = RuntimeEnvironment.getApplication()
    val silent = FakeTone(neverReportsPlaying = true)
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, _) = start(context, ring(fadeSecs = 10)) { _, uri ->
      requested += uri
      if (uri == alarmUri) silent else alternate
    }
    try {
      idleFor(3)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertEquals(2, silent.playCalls)
      assertEquals(1, silent.stopCalls)
      assertEquals(1, alternate.playCalls)
      assertTrue(alternate.playing)
      assertEquals(0.012f, alternate.volumeAtFirstPlay ?: 1f, 0.001f)
      assertEquals(notificationUri.toString(), AlarmStateStore.snapshot(context).ringing?.sourceUri)
      assertTrue(AlarmStateStore.snapshot(context).ringing?.note?.contains("notification sound") == true)
      idleFor(3)
      assertTrue(alternate.volumes.last() in 0.20f..0.26f)
    } finally { controller.destroy() }
  }

  @Test fun laterStoppedDefaultToneAdvancesAndRetainsItsAudibleFadeProgress() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone()
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, _) = start(context, ring(fadeSecs = 10)) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      idleFor(5)
      first.playing = false
      first.neverReportsPlaying = true
      idleFor(3)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertEquals(2, first.playCalls)
      assertEquals(1, first.stopCalls)
      assertTrue(alternate.playing)
      assertTrue((alternate.volumeAtFirstPlay ?: 0f) in 0.28f..0.32f)
      assertEquals(notificationUri.toString(), AlarmStateStore.snapshot(context).ringing?.sourceUri)
    } finally { controller.destroy() }
  }

  @Test fun synchronousPlayFailureStillAdvancesDirectlyToTheNotificationTone() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone(throwsOnPlay = true)
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, _) = start(context, ring()) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertEquals(1, first.playCalls)
      assertEquals(1, first.stopCalls)
      assertTrue(alternate.playing)
      assertEquals(notificationUri.toString(), AlarmStateStore.snapshot(context).ringing?.sourceUri)
    } finally { controller.destroy() }
  }

  @Test fun bothSilentCandidatesExhaustOnceAndFocusGainCannotResetTheBudget() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone(neverReportsPlaying = true)
    val alternate = FakeTone(neverReportsPlaying = true)
    val requested = mutableListOf<Uri>()
    val (controller, service) = start(context, ring()) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      idleFor(5)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertEquals(2, first.playCalls)
      assertEquals(2, alternate.playCalls)
      assertEquals(1, first.stopCalls)
      assertEquals(1, alternate.stopCalls)
      assertFalse(first.playing || alternate.playing)
      val terminal = AlarmStateStore.snapshot(context).ringing
      assertEquals("tone", terminal?.sourceKind)
      assertNull(terminal?.sourceUri)
      assertTrue(terminal?.note?.contains("system alarm sound stopped playing") == true)
      focus(service, AudioManager.AUDIOFOCUS_GAIN)
      idleFor(5)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertEquals(terminal, AlarmStateStore.snapshot(context).ringing)
      assertEquals(2, alternate.playCalls)
    } finally { controller.destroy() }
  }

  @Test fun focusLossSuspendsCandidateRecoveryAndGainResumesTheSameCandidate() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone(neverReportsPlaying = true)
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, service) = start(context, ring()) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      val beforeLoss = watchdog(service)
      focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
      idleFor(5)
      beforeLoss.run()
      assertEquals(listOf(alarmUri), requested)
      assertEquals(1, first.playCalls)
      focus(service, AudioManager.AUDIOFOCUS_GAIN)
      assertEquals(listOf(alarmUri), requested)
      assertEquals(2, first.playCalls)
      idleFor(3)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertTrue(alternate.playing)
    } finally { controller.destroy() }
  }

  @Test fun staleWatchdogCannotStopTheAlternateOrAReplacementOccurrence() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone(neverReportsPlaying = true)
    val alternate = FakeTone()
    val replacementTone = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, service) = start(context, ring()) { _, uri ->
      requested += uri
      if (requested.size == 1) first else if (uri == notificationUri) alternate else replacementTone
    }
    try {
      val oldWatchdog = watchdog(service)
      idleFor(3)
      oldWatchdog.run()
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertEquals(1, alternate.playCalls)
      assertEquals(0, alternate.stopCalls)
      val alternateWatchdog = watchdog(service)
      val replacement = ring().copy(occurrenceId = "scheduled:replacement")
      AlarmStateStore.update(context) { it.copy(ringing = replacement) }
      service.onStartCommand(
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, replacement.occurrenceId), 0, 2,
      )
      oldWatchdog.run()
      alternateWatchdog.run()
      assertEquals(listOf(alarmUri, notificationUri, alarmUri), requested)
      assertTrue(replacementTone.playing)
      assertEquals(1, replacementTone.playCalls)
      assertEquals(0, replacementTone.stopCalls)
      assertEquals(replacement.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertEquals(alarmUri.toString(), AlarmStateStore.snapshot(context).ringing?.sourceUri)
      AlarmPlaybackService.dismissIfMatching(context, replacement.occurrenceId)
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      alternateWatchdog.run()
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertFalse(replacementTone.playing)
      assertEquals(listOf(alarmUri, notificationUri, alarmUri), requested)
    } finally { controller.destroy() }
  }

  @Test fun volumeFailureDuringPlaybackAdvancesToTheNotificationTone() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone()
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val (controller, _) = start(context, ring(fadeSecs = 10)) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      idleFor(3)
      first.throwsOnVolume = true
      idleFor(1)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertFalse(first.playing)
      assertTrue(alternate.playing)
      assertEquals(1, first.stopCalls)
      assertTrue((alternate.volumeAtFirstPlay ?: 1f) in 0.17f..0.21f)
      assertEquals(notificationUri.toString(), AlarmStateStore.snapshot(context).ringing?.sourceUri)
    } finally { controller.destroy() }
  }

  @Test fun candidateChangeDuringAutomaticCompletionKeepsTheGainFalling() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone()
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val active = ring()
    val (controller, _) = start(context, active) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      AlarmPlaybackService.autoStopIfMatching(context, active.occurrenceId)
      idleFor(2)
      first.playing = false
      first.neverReportsPlaying = true
      idleFor(2)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertTrue(alternate.playing)
      assertTrue((alternate.volumeAtFirstPlay ?: 1f) in 0.19f..0.21f)
      assertEquals(active.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      idleFor(2)
      assertTrue(alternate.volumes.zipWithNext().all { (before, after) -> after <= before + 0.0001f })
      assertFalse(alternate.playing)
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { controller.destroy() }
  }

  @Test fun zeroGainDuringAutomaticCompletionCannotRiseWhenFocusRecoveryChangesCandidate() {
    val context = RuntimeEnvironment.getApplication()
    val first = FakeTone()
    val alternate = FakeTone()
    val requested = mutableListOf<Uri>()
    val active = ring()
    val (controller, service) = start(context, active) { _, uri ->
      requested += uri
      if (uri == alarmUri) first else alternate
    }
    try {
      AlarmPlaybackService.autoStopIfMatching(context, active.occurrenceId)
      idleFor(1)
      focus(service, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
      idleFor(1)
      first.throwsOnPlay = true
      focus(service, AudioManager.AUDIOFOCUS_GAIN)
      assertEquals(listOf(alarmUri, notificationUri), requested)
      assertTrue(alternate.playing)
      assertEquals(0f, alternate.volumeAtFirstPlay ?: 1f, 0.001f)
      idleFor(4)
      assertTrue(alternate.volumes.all { it == 0f })
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertFalse(alternate.playing)
    } finally { controller.destroy() }
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
