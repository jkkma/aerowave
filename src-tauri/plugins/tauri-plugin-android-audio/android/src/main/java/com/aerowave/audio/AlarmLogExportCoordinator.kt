package com.aerowave.audio

import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A deadline ends the UI request; admission stays held until native work really returns. */
internal class AlarmLogExportCoordinator(
  private val writer: Executor,
  private val scheduleDeadline: (Runnable, Long) -> (() -> Unit),
  private val deadlineMs: Long = 30_000L,
) {
  private val admitted = AtomicReference<Request?>(null)

  fun isBusy(): Boolean = admitted.get() != null

  fun reserve(callback: (Result<SavedAlarmLogs?>) -> Unit): Request? {
    val request = Request(callback)
    return request.takeIf { admitted.compareAndSet(null, it) }
  }

  inner class Request internal constructor(callback: (Result<SavedAlarmLogs?>) -> Unit) {
    private val completion = AtomicReference<((Result<SavedAlarmLogs?>) -> Unit)?>(callback)
    private val started = AtomicBoolean(false)
    private val snapshotReceived = AtomicBoolean(false)
    private val cancelDeadline = AtomicReference<(() -> Unit)?>(null)
    private var runningThread: Thread? = null

    fun finishWithoutWriting(result: Result<SavedAlarmLogs?>) {
      if (!started.compareAndSet(false, true)) return
      complete(result)
      release()
    }

    fun write(
      capture: ((AlarmEventLog.Snapshot) -> Unit) -> Unit,
      save: (AlarmEventLog.Snapshot) -> SavedAlarmLogs,
    ) {
      if (!started.compareAndSet(false, true)) return
      try {
        val cancel = scheduleDeadline(Runnable(::expire), deadlineMs)
        cancelDeadline.set(cancel)
        if (completion.get() == null) cancelDeadline.getAndSet(null)?.invoke()
        capture { snapshot ->
          if (!snapshotReceived.compareAndSet(false, true)) return@capture
          if (completion.get() == null) {
            release()
            return@capture
          }
          try {
            writer.execute {
              try {
                val canWrite = synchronized(this) {
                  if (completion.get() == null) false
                  else { runningThread = Thread.currentThread(); true }
                }
                if (canWrite) complete(Result.success(save(snapshot)))
              } catch (error: Exception) {
                complete(Result.failure(error))
              } finally {
                synchronized(this) { runningThread = null; Thread.interrupted() }
                release()
              }
            }
          } catch (error: Exception) {
            complete(Result.failure(error))
            release()
          }
        }
      } catch (error: Exception) {
        complete(Result.failure(error))
        if (snapshotReceived.compareAndSet(false, true)) release()
      }
    }

    private fun expire() {
      val callback = synchronized(this) {
        val deliver = completion.getAndSet(null) ?: return
        // Binder/providers may ignore interruption. Keep admission until the
        // worker's finally block, without creating a replacement writer.
        runningThread?.interrupt()
        deliver
      }
      cancelDeadline.getAndSet(null)?.invoke()
      runCatching { callback(Result.failure(TimeoutException(
        "Alarm log export timed out. Android may still be finishing the selected file.",
      ))) }
    }

    private fun complete(result: Result<SavedAlarmLogs?>) {
      val callback = completion.getAndSet(null) ?: return
      cancelDeadline.getAndSet(null)?.invoke()
      runCatching { callback(result) }
    }

    private fun release() { admitted.compareAndSet(this, null) }
  }
}
