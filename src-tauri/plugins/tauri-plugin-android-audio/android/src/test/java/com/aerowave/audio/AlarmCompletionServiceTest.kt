package com.aerowave.audio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmCompletionServiceTest {
  private class Tone : SystemAlarmTone {
    var playing = false
    val volumes = mutableListOf<Float>()
    override fun play() { playing = true }
    override fun stop() { playing = false }
    override fun isPlaying() = playing
    override fun setVolume(volume: Float) { volumes += volume }
  }

  private fun active(fadeSecs: Int = 0, autoSnoozes: Int = 0) = RingingRecord(
    NativeAlarm("fade", "Fade", 7, 0, emptyList(), true, NativeAlarmSource.Station("missing"),
      0.6f, fadeSecs, 1, 0, autoSnoozes),
    "fade-occurrence", "scheduled", System.currentTimeMillis(), SystemClock.elapsedRealtime(),
  )

  private fun start(active: RingingRecord = active()): Triple<org.robolectric.android.controller.ServiceController<AlarmPlaybackService>, AlarmPlaybackService, Tone> {
    val context = RuntimeEnvironment.getApplication()
    AlarmStateStore.update(context) { PersistedAlarmState(initialized = true, alarms = listOf(active.alarm), ringing = active) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    val tone = Tone()
    service.systemToneFactory = { _, _ -> tone }
    service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId), 0, 1)
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(tone.playing)
    return Triple(controller, service, tone)
  }

  private fun idle(ms: Long = 0) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  private fun field(service: AlarmPlaybackService, name: String): Any? =
    AlarmPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
  private fun set(service: AlarmPlaybackService, name: String, value: Any?) =
    AlarmPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)

  @Test fun automaticToneFadeKeepsTheDurableRingUntilZeroAndManualDismissStaysInstant() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, _, tone) = start()
    try {
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle()
      assertEquals(0.6f, tone.volumes.last(), 0.001f)
      assertNotNull(AlarmStateStore.snapshot(context).ringing)
      idle(3_000)
      assertEquals(0.3f, tone.volumes.last(), 0.015f)
      assertTrue(tone.playing)
      assertNotNull(AlarmStateStore.snapshot(context).ringing)
      idle(3_000)
      assertEquals(0f, tone.volumes.last(), 0.001f)
      assertFalse(tone.playing)
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { controller.destroy() }

    val (manualController, _, manualTone) = start(active().copy(occurrenceId = "manual"))
    try {
      AlarmPlaybackService.dismissIfMatching(context, "manual")
      idle()
      assertFalse(manualTone.playing)
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { manualController.destroy() }
  }

  @Test fun timeoutFadeCannotGrowWhileFadeInContinuesOrFocusUnducks() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start(active(fadeSecs = 30))
    try {
      idle(3_000)
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle()
      val first = tone.volumes.last()
      assertTrue(first < 0.1f)
      idle(1_000)
      assertTrue(tone.volumes.last() <= first)
      val focus = field(service, "focusListener") as AudioManager.OnAudioFocusChangeListener
      focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
      idle()
      val ducked = tone.volumes.last()
      focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
      idle()
      assertTrue(tone.volumes.last() <= ducked + 0.0001f)
      idle(5_000)
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { controller.destroy() }
  }

  @Test fun aQueuedDuckBeforeTimeoutCapsTheEnvelopeBeforeItsVolumeTickRuns() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start()
    try {
      val focus = field(service, "focusListener") as AudioManager.OnAudioFocusChangeListener
      focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle()
      assertEquals(0.12f, tone.volumes.last(), 0.001f)
      idle(3_000)
      assertEquals(0.06f, tone.volumes.last(), 0.015f)
      focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
      idle()
      assertTrue(tone.volumes.last() <= 0.061f)
      idle(3_000)
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { controller.destroy() }
  }

  @Test fun manualActionOverridesPendingAutomaticSnoozeAndDuplicateTimeoutDoesNotExtendIt() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, _, tone) = start(active(autoSnoozes = 1))
    try {
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(2_000)
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle()
      assertEquals(0.4f, tone.volumes.last(), 0.015f)
      var callback: PersistedAlarmState? = null
      AlarmPlaybackService.stopIfMatching(context, "fade-occurrence", false) { callback = it }
      idle()
      assertNotNull(callback)
      assertNull(callback!!.ringing)
      assertTrue(callback!!.snoozes.isEmpty())
      assertFalse(tone.playing)
      idle(6_000)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
    } finally { controller.destroy() }
  }

  @Test fun duplicateAutomaticSnoozeCommitsOnceAtTheOriginalFadeDeadline() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val (controller, _, tone) = start(active(autoSnoozes = 1))
    try {
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(4_000)
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(2_000)
      val state = AlarmStateStore.snapshot(context)
      assertNull(state.ringing)
      assertEquals(1, state.snoozes.getValue("fade").autoSnoozesUsed)
      assertFalse(tone.playing)
      assertEquals(1, Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .count { it.alarmClockInfo != null })
    } finally { controller.destroy() }
  }

  @Test fun manualSnoozeImmediatelyOverridesAutomaticDismissWithoutUsingAutoAllowance() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val (controller, _, tone) = start()
    try {
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(2_000)
      var callback: PersistedAlarmState? = null
      AlarmPlaybackService.stopIfMatching(context, "fade-occurrence", true) { callback = it }
      idle()
      assertNotNull(callback)
      assertNull(callback!!.ringing)
      assertEquals(0, callback!!.snoozes.getValue("fade").autoSnoozesUsed)
      assertFalse(tone.playing)
      idle(6_000)
      assertEquals(1, AlarmStateStore.snapshot(context).snoozes.size)
    } finally { controller.destroy() }
  }

  @Test fun deniedExactAccessRetainsTheAudibleRingAndReturnsFailureForRetry() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(false)
    val (controller, _, tone) = start()
    try {
      var failed = false
      AlarmPlaybackService.volumeButtonIfMatching(context, "fade-occurrence") { failed = true }
      idle()
      assertTrue(failed)
      assertEquals("fade-occurrence", AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
      assertTrue(tone.playing)
      assertEquals(0.6f, tone.volumes.last(), 0.001f)
    } finally { controller.destroy() }
  }

  @Test fun destroyingTheServiceCancelsItsFadeAndCannotCompleteTheDurableRingLater() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start()
    AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
    idle(2_000)
    val wake = field(service, "ringWakeLock") as PowerManager.WakeLock
    assertTrue(wake.isHeld)
    controller.destroy()
    idle(7_000)
    assertFalse(wake.isHeld)
    assertFalse(tone.playing)
    assertEquals("fade-occurrence", AlarmStateStore.snapshot(context).ringing?.occurrenceId)
    assertNull(AlarmPlaybackService.liveOccurrenceId(context))
  }

  @Test
  @Config(shadows = [RejectingAlarmManager::class])
  fun failedAutomaticSnoozeRestoresSoundFocusWakeAndAllowsManualRetry() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start(active(autoSnoozes = 1))
    try {
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(6_000)
      val state = AlarmStateStore.snapshot(context)
      assertEquals("fade-occurrence", state.ringing?.occurrenceId)
      assertTrue(state.snoozes.isEmpty())
      assertEquals(0, state.ringing?.autoSnoozesUsed)
      assertTrue(tone.playing)
      assertEquals(0.6f, tone.volumes.last(), 0.001f)
      assertTrue(field(service, "focusGranted") as Boolean)
      assertTrue((field(service, "ringWakeLock") as PowerManager.WakeLock).isHeld)
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(7_000)
      assertEquals(0.6f, tone.volumes.last(), 0.001f)
      var retryState: PersistedAlarmState? = null
      AlarmPlaybackService.stopIfMatching(context, "fade-occurrence", false) { retryState = it }
      idle()
      assertNotNull(retryState)
      assertNull(retryState!!.ringing)
      assertFalse(tone.playing)
    } finally { controller.destroy() }
  }

  @Test fun failedPreferenceCommitRestoresTheRingAndVolumeButtonFailureCallback() {
    val context = RuntimeEnvironment.getApplication()
    ShadowAlarmManager.setCanScheduleExactAlarms(true)
    val (controller, service, tone) = start()
    try {
      val base = service.baseContext
      val preferences = base.createDeviceProtectedStorageContext()
        .getSharedPreferences("aerowave_android_alarms", Context.MODE_PRIVATE)
      var reject = true
      val wrapped = object : SharedPreferences by preferences {
        override fun edit(): SharedPreferences.Editor {
          val editor = preferences.edit()
          return object : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
              editor.putString(key, value)
              return this
            }
            override fun commit(): Boolean {
              val saved = editor.commit()
              return if (reject) { reject = false; false } else saved
            }
          }
        }
      }
      val replacement = object : ContextWrapper(base) {
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
          if (name == "aerowave_android_alarms") wrapped else super.getSharedPreferences(name, mode)
      }
      ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(service, replacement)
      var failed = false
      AlarmPlaybackService.volumeButtonIfMatching(context, "fade-occurrence") { failed = true }
      idle()
      assertTrue(failed)
      assertEquals("fade-occurrence", AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
      assertTrue(tone.playing)
      assertEquals(0.6f, tone.volumes.last(), 0.001f)
      assertTrue(Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .none { it.alarmClockInfo != null })
      AlarmPlaybackService.dismissIfMatching(context, "fade-occurrence")
      idle()
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { controller.destroy() }
  }

  @Test fun aReplacementOccurrenceCancelsAnOldFadeWithoutTouchingItsSoundOrDurableState() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start()
    try {
      AlarmPlaybackService.autoStopIfMatching(context, "fade-occurrence")
      idle(2_000)
      val replacement = active().copy(occurrenceId = "replacement", alarm = active().alarm.copy(id = "other"))
      AlarmStateStore.update(context) { it.copy(alarms = it.alarms + replacement.alarm, ringing = replacement) }
      service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, replacement.occurrenceId), 0, 2)
      idle(7_000)
      assertEquals("replacement", AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(tone.playing)
      assertEquals(0.6f, tone.volumes.last(), 0.001f)
      AlarmPlaybackService.dismissIfMatching(context, "fade-occurrence")
      idle()
      assertEquals("replacement", AlarmStateStore.snapshot(context).ringing?.occurrenceId)
    } finally { controller.destroy() }
  }

  @Test
  @Config(shadows = [FlippingAlarmManager::class])
  fun revokedExactAccessInsideTheArmCallCannotAcceptASilentSnooze() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, _, tone) = start()
    try {
      FlippingAlarmManager.checks = 0
      var failed = false
      AlarmPlaybackService.volumeButtonIfMatching(context, "fade-occurrence") { failed = true }
      idle()
      assertTrue(failed)
      assertEquals(3, FlippingAlarmManager.checks)
      assertEquals("fade-occurrence", AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertTrue(AlarmStateStore.snapshot(context).snoozes.isEmpty())
      assertTrue(tone.playing)
    } finally { controller.destroy() }
  }

  @Test fun mediaStationAndFolderUseTheSameSixSecondEnvelopeAsTheTone() {
    val context = RuntimeEnvironment.getApplication()
    for (kind in listOf("station", "folder", "backup")) {
      val (controller, service, tone) = start(active().copy(occurrenceId = "media-$kind"))
      try {
        (field(service, "player") as ExoPlayer).release()
        tone.stop()
        set(service, "systemTone", null)
        set(service, "sourceKind", kind)
        var volume = 0.6f
        var playing = true
        val started = SystemClock.elapsedRealtime()
        val fake = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
          when (method.name) {
            "getVolume" -> volume
            "setVolume" -> { volume = args!![0] as Float; null }
            "isPlaying", "getPlayWhenReady" -> playing
            "getCurrentPosition", "getBufferedPosition" -> SystemClock.elapsedRealtime() - started
            "getCurrentTracks" -> Tracks.EMPTY
            "getPlaybackState" -> Player.STATE_READY
            "stop", "pause", "release" -> { playing = false; null }
            "play" -> { playing = true; null }
            "hashCode" -> 7
            "equals" -> false
            "toString" -> "Audit player"
            else -> when (method.returnType) {
              java.lang.Boolean.TYPE -> false
              java.lang.Integer.TYPE -> 0
              java.lang.Long.TYPE -> 0L
              java.lang.Float.TYPE -> 0f
              else -> null
            }
          }
        } as ExoPlayer
        set(service, "player", fake)
        AlarmPlaybackService.autoStopIfMatching(context, "media-$kind")
        idle(3_000)
        assertEquals("$kind must reach half its original gain", 0.3f, volume, 0.015f)
        assertTrue(playing)
        if (kind == "station") {
          AlarmPlaybackService::class.java.getDeclaredMethod("playTone", String::class.java)
            .apply { isAccessible = true }.invoke(service, "Synthetic station failure during fade")
          assertTrue(tone.playing)
          assertEquals("A fallback must retain the finishing gain", 0.3f, tone.volumes.last(), 0.015f)
        }
        idle(3_000)
        assertEquals(0f, volume, 0.001f)
        assertFalse(playing)
        assertNull(AlarmStateStore.snapshot(context).ringing)
      } finally { controller.destroy() }
    }
  }

  @Implements(AlarmManager::class)
  class RejectingAlarmManager : ShadowAlarmManager() {
    @Implementation override fun canScheduleExactAlarms(): Boolean = true
    @Implementation override fun setAlarmClock(info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
      throw SecurityException("Synthetic snooze arming rejection")
    }
  }

  @Implements(AlarmManager::class)
  class FlippingAlarmManager : ShadowAlarmManager() {
    @Implementation override fun canScheduleExactAlarms(): Boolean = ++checks < 3
    companion object { var checks = 0 }
  }
}
