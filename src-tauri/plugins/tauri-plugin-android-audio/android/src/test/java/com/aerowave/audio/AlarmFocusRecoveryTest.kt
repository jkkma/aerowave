package com.aerowave.audio

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmFocusRecoveryTest {
  private class Tone : SystemAlarmTone {
    var playing = false
    var playCalls = 0
    var onStop: () -> Unit = {}
    override fun play() { playing = true; playCalls++ }
    override fun stop() { playing = false; onStop() }
    override fun isPlaying() = playing
    override fun setVolume(volume: Float) = Unit
  }

  private fun active(occurrence: String = "focus:one") = RingingRecord(
    NativeAlarm("focus", "Focus", 7, 0, emptyList(), true, NativeAlarmSource.Station("missing"),
      0.6f, 0, 1, 0, 0),
    occurrence, "scheduled", System.currentTimeMillis(), SystemClock.elapsedRealtime(),
  )

  private fun start(): Triple<org.robolectric.android.controller.ServiceController<AlarmPlaybackService>, AlarmPlaybackService, Tone> {
    val context = RuntimeEnvironment.getApplication()
    val current = active()
    AlarmStateStore.update(context) { PersistedAlarmState(initialized = true, alarms = listOf(current.alarm), ringing = current) }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    val tone = Tone()
    service.systemToneFactory = { _, _ -> tone }
    service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, current.occurrenceId), 0, 1)
    idle()
    assertTrue(tone.playing)
    return Triple(controller, service, tone)
  }

  private fun idle(ms: Long = 0) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  private fun focus(service: AlarmPlaybackService, change: Int) {
    (AlarmPlaybackService::class.java.getDeclaredField("focusListener")
      .apply { isAccessible = true }.get(service) as AudioManager.OnAudioFocusChangeListener)
      .onAudioFocusChange(change)
  }

  @Test fun permanentLossRecoversAfterALongCallEndsWithoutWaitingForAGainCallback() {
    val context = RuntimeEnvironment.getApplication()
    val manager = context.getSystemService(AudioManager::class.java)
    val (controller, service, tone) = start()
    try {
      manager.mode = AudioManager.MODE_IN_COMMUNICATION
      focus(service, AudioManager.AUDIOFOCUS_LOSS)
      idle(90_000L)
      assertFalse(tone.playing)
      assertEquals(1, tone.playCalls)
      assertTrue(AlarmPlaybackService.canResumeSound("focus:one"))
      assertTrue(AlarmStateStore.snapshot(context).ringing!!.note!!.contains("Resume sound"))
      manager.mode = AudioManager.MODE_NORMAL
      idle(1_000L)
      assertTrue(tone.playing)
      assertEquals(2, tone.playCalls)
      assertFalse(AlarmPlaybackService.canResumeSound("focus:one"))
      assertFalse(AlarmStateStore.snapshot(context).ringing!!.note!!.contains("interrupted"))
    } finally { manager.mode = AudioManager.MODE_NORMAL; controller.destroy() }
  }

  @Test fun anotherPermanentLossCannotStartAnAutomaticFocusFightButNotificationResumeWorks() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start()
    try {
      focus(service, AudioManager.AUDIOFOCUS_LOSS)
      idle(1_000L)
      assertEquals(2, tone.playCalls)
      focus(service, AudioManager.AUDIOFOCUS_LOSS)
      idle(60_000L)
      assertFalse(tone.playing)
      assertEquals(2, tone.playCalls)
      assertTrue(AlarmPlaybackService.canResumeSound("focus:one"))
      val actions = Shadows.shadowOf(service).lastForegroundNotification.actions.orEmpty()
      val posted = context.getSystemService(android.app.NotificationManager::class.java)
      val notification = Shadows.shadowOf(posted).getNotification(AlarmPlaybackService.NOTIFICATION_ID)
      assertTrue((notification?.actions ?: actions).any { it.title.toString() == "Resume sound" })
      AlarmActionReceiver().onReceive(context, Intent().setAction(AlarmPlaybackService.ACTION_RESUME_SOUND)
        .putExtra("occurrenceId", "focus:one"))
      idle()
      assertTrue(tone.playing)
      assertEquals(3, tone.playCalls)
    } finally { controller.destroy() }
  }

  @Test fun theNativeResumeButtonRetriesOnlyTheMatchingRing() {
    val context = RuntimeEnvironment.getApplication()
    val manager = context.getSystemService(AudioManager::class.java)
    val (controller, service, tone) = start()
    val activityController = Robolectric.buildActivity(AlarmActivity::class.java,
      AlarmActivity.intentFor(context, "focus:one")).create().start().resume()
    try {
      manager.mode = AudioManager.MODE_IN_COMMUNICATION
      focus(service, AudioManager.AUDIOFOCUS_LOSS)
      idle(500L)
      val button = AlarmActivity::class.java.getDeclaredField("resumeButton")
        .apply { isAccessible = true }.get(activityController.get()) as Button
      assertEquals(View.VISIBLE, button.visibility)
      AlarmPlaybackService.resumeSoundIfMatching(context, "focus:old")
      idle()
      assertFalse(tone.playing)
      manager.mode = AudioManager.MODE_NORMAL
      button.performClick()
      idle()
      assertTrue(tone.playing)
      assertEquals(2, tone.playCalls)
    } finally {
      manager.mode = AudioManager.MODE_NORMAL
      activityController.pause().stop().destroy()
      controller.destroy()
    }
  }

  @Test fun dismissalAndQueuedOldFocusCallbacksCannotAffectAReplacementRing() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start()
    try {
      focus(service, AudioManager.AUDIOFOCUS_LOSS)
      val replacement = active("focus:replacement")
      AlarmStateStore.update(context) { it.copy(ringing = replacement) }
      service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, replacement.occurrenceId), 0, 2)
      idle(2_000L)
      assertTrue(tone.playing)
      assertFalse(AlarmPlaybackService.canResumeSound(replacement.occurrenceId))
      focus(service, AudioManager.AUDIOFOCUS_LOSS)
      idle()
      AlarmPlaybackService.dismissIfMatching(context, replacement.occurrenceId)
      idle(5_000L)
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertFalse(tone.playing)
      assertFalse(AlarmPlaybackService.canResumeSound(replacement.occurrenceId))
    } finally { controller.destroy() }
  }

  @Test fun aClaimRetiredBeforeServiceBindingCannotStartAnOrphanRing() {
    val context = RuntimeEnvironment.getApplication()
    val (controller, service, tone) = start()
    try {
      val replacement = active("focus:replacement")
      AlarmStateStore.update(context) { it.copy(ringing = replacement) }
      AlarmPlaybackService.markPending(replacement.occurrenceId)
      // Replacing the old output creates the window between reading the new
      // claim and binding it. Retire it there as the handoff timeout can.
      tone.onStop = { AlarmStateStore.update(context) { it.copy(ringing = null) } }
      service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, replacement.occurrenceId), 0, 2)
      idle(2_000L)
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertNull(AlarmPlaybackService.liveOccurrenceId(context))
      assertNull(AlarmPlaybackService.activeOccurrenceId())
      assertFalse(AlarmPlaybackService.isRinging())
      assertFalse(tone.playing)
      assertEquals(1, tone.playCalls)
    } finally { controller.destroy() }
  }
}
