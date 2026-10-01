package com.aerowave.audio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID

internal object AlarmServiceStarter {
  internal const val WAKE_TIMEOUT_MS = 30_000L
  private const val EXTRA_WAKE_TOKEN = "com.aerowave.audio.extra.START_WAKE_TOKEN"
  // Cold service initialization runs on the main thread. Its deadline must
  // still expire if that thread is blocked before it can finish the handoff.
  private val handler = Handler(HandlerThread("AerowaveAlarmStartExpiry").apply { start() }.looper)
  private val pending = mutableMapOf<String, Handoff>()

  fun start(context: Context, intent: Intent) {
    val token = UUID.randomUUID().toString()
    val wakeLock = context.getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:alarm-service-start")
      .apply { setReferenceCounted(false) }
    val occurrence = intent.getStringExtra("occurrenceId")
    val startedElapsedMs = SystemClock.elapsedRealtime()
    val timeout = Runnable { complete(token, "timeout") }
    // AlarmManager's CPU hold ends with onReceive. Keep the CPU running until
    // the service has acquired its own preparation/ringing hold, even if cold
    // player initialization outlasts the temporary idle exemption.
    wakeLock.acquire(WAKE_TIMEOUT_MS)
    synchronized(pending) { pending[token] = Handoff(wakeLock, timeout, context.applicationContext ?: context, occurrence, startedElapsedMs) }
    AlarmEventLog.record(context, "startup.wake_acquired", occurrence,
      mapOf("timeoutMs" to WAKE_TIMEOUT_MS))
    handler.postDelayed(timeout, WAKE_TIMEOUT_MS)
    try {
      ContextCompat.startForegroundService(context, Intent(intent).putExtra(EXTRA_WAKE_TOKEN, token))
      AlarmEventLog.record(context, "startup.service_start_accepted", occurrence)
    } catch (error: Throwable) {
      AlarmEventLog.record(context, "startup.service_start_failed", occurrence, error = error)
      complete(token, "start_rejected")
      throw error
    }
  }

  fun complete(intent: Intent?) {
    intent?.getStringExtra(EXTRA_WAKE_TOKEN)?.let { complete(it, "service_completed") }
  }

  private fun complete(token: String, reason: String) {
    // PREPARE and FIRE can overlap; one completed or stale delivery must never
    // release the wake hold for another service start still awaiting setup.
    val handoff = synchronized(pending) { pending.remove(token) } ?: return
    handler.removeCallbacks(handoff.timeout)
    if (handoff.wakeLock.isHeld) handoff.wakeLock.release()
    AlarmEventLog.record(handoff.context, "startup.wake_released", handoff.occurrenceId,
      mapOf("reason" to reason, "durationMs" to SystemClock.elapsedRealtime() - handoff.startedElapsedMs))
  }

  private data class Handoff(
    val wakeLock: PowerManager.WakeLock, val timeout: Runnable, val context: Context,
    val occurrenceId: String?, val startedElapsedMs: Long,
  )
}
