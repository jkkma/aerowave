package com.aerowave.audio

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
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
class AlarmSourceRecoveryTest {
  private class Tone : SystemAlarmTone {
    var playing = false
    var stopCalls = 0
    val volumes = mutableListOf<Float>()
    override fun play() { playing = true }
    override fun stop() { playing = false; stopCalls++ }
    override fun isPlaying() = playing
    override fun setVolume(volume: Float) { volumes += volume }
  }

  private fun active(occurrence: String = "source:one", station: Boolean = false) = RingingRecord(
    NativeAlarm("source", "Source", 7, 0, emptyList(), true,
      if (station) NativeAlarmSource.Station("station") else NativeAlarmSource.Folder("content://fixture/tree/music"),
      0.6f, 0, 1, 0, 0),
    occurrence, "scheduled", System.currentTimeMillis(), SystemClock.elapsedRealtime(),
  )

  private fun idle(ms: Long = 0) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

  /** Continues after cancellation, like a provider stuck in an uninterruptible Binder call. */
  private class UncooperativeOperation : Executor {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    private var thread: Thread? = null
    override fun execute(command: Runnable) {
      thread = Thread(command, "alarm-provider-fixture").apply { isDaemon = true; start() }
    }
    fun waitForRelease() {
      started.countDown()
      while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
    }
    fun finish() {
      release.countDown()
      thread?.join(5_000L)
      assertFalse("The released fixture must exit", thread?.isAlive == true)
    }
  }

  private fun start(
    active: RingingRecord,
    tone: Tone,
    configure: (AlarmPlaybackService) -> Unit,
  ): org.robolectric.android.controller.ServiceController<AlarmPlaybackService> {
    val context = RuntimeEnvironment.getApplication()
    AlarmStateStore.update(context) {
      PersistedAlarmState(initialized = true, alarms = listOf(active.alarm), ringing = active,
        stations = listOf(NativeStation("station", "Station", "https://radio.test/live")))
    }
    val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
    val service = controller.get()
    service.systemToneFactory = { _, _ -> tone }
    configure(service)
    service.onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, active.occurrenceId), 0, 1)
    return controller
  }

  @Test fun uncooperativeFolderAndDnsOperationsReachToneAtTheOuterDeadlineAndCannotReturnLate() {
    val context = RuntimeEnvironment.getApplication()
    for (station in listOf(false, true)) {
      val operation = UncooperativeOperation()
      val tone = Tone()
      val controller = start(active("source:$station", station), tone) { service ->
        service.sourceExecutor = operation
        service.folderTrackResolver = { _, _, _ ->
          operation.waitForRelease()
          TrackPick("content://fixture/late-track", "Late track", 1)
        }
        service.stationStreamResolver = { _, _ ->
          operation.waitForRelease()
          ResolvedAlarmStream("https://radio.test/late", false)
        }
      }
      try {
        assertTrue(operation.started.await(5, TimeUnit.SECONDS))
        idle(AlarmPlaybackService.SOURCE_SELECTION_TIMEOUT_MS - 1)
        assertFalse(tone.playing)
        idle(1)
        assertTrue(tone.playing)
        assertEquals("tone", AlarmStateStore.snapshot(context).ringing?.sourceKind)
        operation.finish()
        idle()
        assertTrue(tone.playing)
        assertEquals(0, tone.stopCalls)
        assertEquals("tone", AlarmStateStore.snapshot(context).ringing?.sourceKind)
      } finally {
        operation.finish()
        controller.destroy()
      }
    }
  }

  @Test fun fallbackImmediatelyInvalidatesAReplyEvenBeforeItsSelectionDeadline() {
    val context = RuntimeEnvironment.getApplication()
    val operation = UncooperativeOperation()
    val tone = Tone()
    val controller = start(active(), tone) { service ->
      service.sourceExecutor = operation
      service.folderTrackResolver = { _, _, _ ->
        operation.waitForRelease()
        TrackPick("content://fixture/late-track", "Late track", 1)
      }
    }
    try {
      assertTrue(operation.started.await(5, TimeUnit.SECONDS))
      AlarmPlaybackService::class.java.getDeclaredMethod("playTone", String::class.java)
        .apply { isAccessible = true }.invoke(controller.get(), "Source recovery fixture")
      assertTrue(tone.playing)
      operation.finish()
      idle()
      assertTrue(tone.playing)
      assertEquals(0, tone.stopCalls)
      assertEquals("tone", AlarmStateStore.snapshot(context).ringing?.sourceKind)
      idle(AlarmPlaybackService.SOURCE_SELECTION_TIMEOUT_MS + 1)
      assertTrue(tone.playing)
    } finally { operation.finish(); controller.destroy() }
  }

  @Test fun dismissalCancelsTheSelectionDeadlineAndRejectsAnUncooperativeLateReply() {
    val context = RuntimeEnvironment.getApplication()
    val operation = UncooperativeOperation()
    val tone = Tone()
    val controller = start(active(), tone) { service ->
      service.sourceExecutor = operation
      service.folderTrackResolver = { _, _, _ ->
        operation.waitForRelease()
        TrackPick("content://fixture/late-track", "Late track", 1)
      }
    }
    try {
      assertTrue(operation.started.await(5, TimeUnit.SECONDS))
      AlarmPlaybackService.dismissIfMatching(context, "source:one")
      idle()
      operation.finish()
      idle(AlarmPlaybackService.SOURCE_SELECTION_TIMEOUT_MS + 1)
      assertNull(AlarmStateStore.snapshot(context).ringing)
      assertFalse(tone.playing)
    } finally { operation.finish(); controller.destroy() }
  }

  @Test fun aReplacementOccurrenceCannotReceiveAnOldProviderReplyOrTimeout() {
    val context = RuntimeEnvironment.getApplication()
    val operation = UncooperativeOperation()
    val tone = Tone()
    val controller = start(active(), tone) { service ->
      service.sourceExecutor = operation
      service.folderTrackResolver = { _, _, _ ->
        operation.waitForRelease()
        TrackPick("content://fixture/old-track", "Old track", 1)
      }
    }
    try {
      assertTrue(operation.started.await(5, TimeUnit.SECONDS))
      val next = active("source:replacement").copy(alarm = active().alarm.copy(source = NativeAlarmSource.Station("missing")))
      AlarmStateStore.update(context) { it.copy(ringing = next) }
      controller.get().onStartCommand(AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, next.occurrenceId), 0, 2)
      assertTrue(tone.playing)
      operation.finish()
      idle(AlarmPlaybackService.SOURCE_SELECTION_TIMEOUT_MS + 1)
      assertTrue(tone.playing)
      assertEquals(0, tone.stopCalls)
      assertEquals(next.occurrenceId, AlarmStateStore.snapshot(context).ringing?.occurrenceId)
      assertEquals("tone", AlarmStateStore.snapshot(context).ringing?.sourceKind)
    } finally { operation.finish(); controller.destroy() }
  }

  @Test fun lateProviderSuccessCannotRestartAudioOrRaiseAnAutomaticCompletionFade() {
    val context = RuntimeEnvironment.getApplication()
    val operation = UncooperativeOperation()
    val tone = Tone()
    val controller = start(active(), tone) { service ->
      service.sourceExecutor = operation
      service.folderTrackResolver = { _, _, _ ->
        operation.waitForRelease()
        TrackPick("content://fixture/late-track", "Late track", 1)
      }
    }
    try {
      assertTrue(operation.started.await(5, TimeUnit.SECONDS))
      AlarmPlaybackService::class.java.getDeclaredMethod("playTone", String::class.java)
        .apply { isAccessible = true }.invoke(controller.get(), "Source recovery fixture")
      AlarmPlaybackService.autoStopIfMatching(context, "source:one")
      idle(2_000L)
      val finishingVolume = tone.volumes.last()
      operation.finish()
      idle()
      assertTrue(tone.playing)
      assertTrue(tone.volumes.last() <= finishingVolume + 0.0001f)
      idle(4_000L)
      assertFalse(tone.playing)
      assertNull(AlarmStateStore.snapshot(context).ringing)
      idle(AlarmPlaybackService.SOURCE_SELECTION_TIMEOUT_MS)
      assertFalse(tone.playing)
      assertNull(AlarmStateStore.snapshot(context).ringing)
    } finally { operation.finish(); controller.destroy() }
  }

  @Test fun workerCapacityCannotQueueWorkOrGrowAfterAnUncooperativeCancellation() {
    val pool = AlarmSourceTaskPool(maxWorkers = 1)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val job = java.util.concurrent.FutureTask<Unit>({
      started.countDown()
      while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
      finished.countDown()
    }, Unit)
    try {
      pool.execute(job)
      assertTrue(started.await(5, TimeUnit.SECONDS))
      job.cancel(true)
      repeat(3) {
        try {
          pool.execute { fail("Rejected work must not queue") }
          fail("A cancelled but running provider still owns its worker slot")
        } catch (_: RejectedExecutionException) { }
      }
    } finally {
      release.countDown()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      pool.shutdown()
    }
  }

  @Test fun exhaustedWorkerCapacityFallsBackSynchronouslyInsteadOfWaitingInAQueue() {
    val context = RuntimeEnvironment.getApplication()
    val tone = Tone()
    val controller = start(active(), tone) { service ->
      service.sourceExecutor = Executor { throw RejectedExecutionException("Occupied provider slots") }
    }
    try {
      assertTrue(tone.playing)
      assertEquals("tone", AlarmStateStore.snapshot(context).ringing?.sourceKind)
      idle(AlarmPlaybackService.SOURCE_SELECTION_TIMEOUT_MS + 1)
      assertTrue(tone.playing)
    } finally { controller.destroy() }
  }
}
