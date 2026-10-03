package com.aerowave.audio

import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A provider that ignores interruption retains its slot across service recreation. */
internal class AlarmSourceTaskPool(maxWorkers: Int = 2) : Executor {
  private val pool = ThreadPoolExecutor(
    0, maxWorkers, 60L, TimeUnit.SECONDS, SynchronousQueue(),
    { task -> Thread(task, "aerowave-alarm-source").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
  )

  override fun execute(command: Runnable) = pool.execute(command)

  internal fun shutdown() = pool.shutdownNow()
}

internal object AlarmSourceTasks : Executor {
  private val pool = AlarmSourceTaskPool()
  override fun execute(command: Runnable) = pool.execute(command)
}
