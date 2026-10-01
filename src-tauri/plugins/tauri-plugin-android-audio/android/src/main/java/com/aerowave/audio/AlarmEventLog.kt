package com.aerowave.audio

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Private release diagnostics. Fixed slots are overwritten, never deleted or cleared by export. */
internal object AlarmEventLog {
  internal const val SLOT_COUNT = 4
  internal const val SLOT_BYTES = 384 * 1024
  internal const val QUEUE_CAPACITY = 256
  internal const val MAX_RECORD_BYTES = 8 * 1024

  data class Status(
    val sessionId: String,
    val droppedRecords: Long,
    val writeFailures: Long,
    val retainedBytes: Int,
    val queueDepth: Int,
    val partial: Boolean,
    val snapshotFailure: String?,
    val historyTruncated: Boolean = false,
    val recoveredIncompleteData: Boolean = false,
  )

  data class Snapshot(val jsonl: ByteArray, val status: Status)

  @Volatile private var engine: AlarmLogEngine? = null

  fun record(
    context: Context,
    event: String,
    occurrenceId: String? = null,
    fields: Map<String, Any?> = emptyMap(),
    error: Throwable? = null,
  ) {
    runCatching { engine(context).record(event, occurrenceId, fields, error) }
  }

  /** Binder and package inspection happens on the writer, after the caller has returned. */
  fun recordSystemContext(
    context: Context,
    event: String,
    occurrenceId: String? = null,
    fields: Map<String, Any?> = emptyMap(),
    error: Throwable? = null,
  ) {
    runCatching {
      val app = context.applicationContext ?: context
      engine(app).record(event, occurrenceId, fields, error) { systemContext(app) }
    }
  }

  /** Normally called on the writer. Queue rejection reports a failure synchronously; never do UI work here. */
  fun snapshot(context: Context, callback: (Snapshot) -> Unit) {
    try {
      engine(context).snapshot(callback)
    } catch (_: Throwable) {
      runCatching { callback(Snapshot(ByteArray(0), Status("unavailable", 0, 1, 0, 0, true, "logger_unavailable"))) }
    }
  }

  private fun engine(context: Context): AlarmLogEngine {
    engine?.let { return it }
    return synchronized(this) {
      engine ?: run {
        val app = context.applicationContext ?: context
        AlarmLogEngine(
          directory = { File(app.createDeviceProtectedStorageContext().filesDir, "alarm-events") },
          elapsedMs = SystemClock::elapsedRealtime,
          sessionFields = { buildContext(app) + systemContext(app) + mapOf("pid" to Process.myPid()) },
          previousExits = { previousExits(app) },
        ).also { writer ->
          engine = writer
          // Android's handler must still receive every exception. Skip the hook
          // if no original handler exists rather than changing termination policy.
          Thread.getDefaultUncaughtExceptionHandler()?.let { original ->
            runCatching {
              Thread.setDefaultUncaughtExceptionHandler(AlarmFatalHandler(
                original,
                report = { thread, error ->
                  writer.record("process.uncaught", fields = mapOf("threadId" to thread.id), error = error)
                },
                flush = writer::flush,
              ))
            }
          }
        }
      }
    }
  }

  private fun buildContext(context: Context): Map<String, Any?> = buildMap {
    put("sdk", Build.VERSION.SDK_INT)
    runCatching {
      @Suppress("DEPRECATION")
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      put("version", info.versionName)
      put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong())
      put("lastUpdateTimeMs", info.lastUpdateTime)
    }.onFailure { put("buildReadFailed", true) }
  }

  private fun systemContext(context: Context): Map<String, Any?> = buildMap {
    var failures = 0
    fun inspect(key: String, read: () -> Any?) {
      try { put(key, read()) } catch (_: Throwable) { failures++ }
    }
    val power = context.getSystemService(PowerManager::class.java)
    val audio = context.getSystemService(AudioManager::class.java)
    inspect("interactive") { power.isInteractive }
    inspect("deviceIdle") { power.isDeviceIdleMode }
    inspect("powerSave") { power.isPowerSaveMode }
    inspect("batteryOptimizationIgnored") { power.isIgnoringBatteryOptimizations(context.packageName) }
    inspect("charging") { context.getSystemService(BatteryManager::class.java).isCharging }
    inspect("dndActive") { AlarmDnd.active(context) }
    inspect("exactAllowed") {
      Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    inspect("notificationsEnabled") { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    inspect("alarmChannelImportance") { AlarmPlaybackService.alarmChannelImportance(context) }
    inspect("fullScreenAllowed") {
      Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
    inspect("overlayAllowed") { Settings.canDrawOverlays(context) }
    inspect("mediaVolumeIndex") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
    inspect("mediaVolumeMax") { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    inspect("mediaMuted") { audio.isStreamMute(AudioManager.STREAM_MUSIC) }
    inspect("availableOutputDeviceTypes") { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.distinct().sorted().take(12) }
    put("contextReadFailures", failures)
  }

  private fun previousExits(context: Context): List<Map<String, Any?>> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return runCatching {
      context.getSystemService(ActivityManager::class.java)
        .getHistoricalProcessExitReasons(context.packageName, 0, 3).map {
          // System descriptions, traces and process-state summaries can contain
          // arbitrary user data. Numeric exit reasons still identify crashes/kills.
          mapOf("exitReason" to it.reason, "exitStatus" to it.status, "exitAtMs" to it.timestamp,
            "exitImportance" to it.importance, "exitPid" to it.pid)
        }
    }.getOrElse { listOf(mapOf("exitHistoryReadFailed" to true)) }
  }
}

internal class AlarmFatalHandler(
  private val original: Thread.UncaughtExceptionHandler,
  private val report: (Thread, Throwable) -> Unit,
  private val flush: (Long) -> Unit,
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(thread: Thread, error: Throwable) {
    try {
      runCatching { report(thread, error) }
      runCatching { flush(150L) }
    } finally {
      original.uncaughtException(thread, error)
    }
  }
}

/** A single bounded writer keeps filesystem work and context inspection off alarm delivery. */
internal class AlarmLogEngine(
  private val directory: () -> File,
  private val wallMs: () -> Long = System::currentTimeMillis,
  private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
  private val zoneId: () -> String = { ZoneId.systemDefault().id },
  private val sessionFields: () -> Map<String, Any?> = { emptyMap() },
  private val previousExits: () -> List<Map<String, Any?>> = { emptyList() },
  private val slotCount: Int = AlarmEventLog.SLOT_COUNT,
  private val slotBytes: Int = AlarmEventLog.SLOT_BYTES,
  queueCapacity: Int = AlarmEventLog.QUEUE_CAPACITY,
  private val appendRow: (File, ByteArray) -> Unit = ::appendAlarmLogRow,
) {
  val sessionId: String = UUID.randomUUID().toString()
  private val processId = UUID.randomUUID().toString()
  private val dropped = AtomicLong()
  private val failures = AtomicLong()
  private val submissionLock = Any()
  @Volatile private var writerThread: Thread? = null
  @Volatile private var retainedBytes = 0
  @Volatile private var historyPartial = false
  @Volatile private var historyTruncated = false
  @Volatile private var recoveredIncompleteData = false
  private var store: AlarmLogStore? = null
  private var sequence = 0L
  private var sessionStarted = false
  private var reportedDrops = 0L
  private var reportedFailures = 0L
  private val executor = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity),
    { task -> Thread(task, "AerowaveAlarmLog").apply { isDaemon = true; writerThread = this } },
    ThreadPoolExecutor.AbortPolicy(),
  )

  fun record(
    event: String,
    occurrenceId: String? = null,
    fields: Map<String, Any?> = emptyMap(),
    error: Throwable? = null,
    contextFields: (() -> Map<String, Any?>)? = null,
  ) {
    try {
      val captured = Captured(safeEvent(event), occurrenceId?.let(::fingerprint), sanitizeFields(fields),
        error?.let(::sanitizeError), wallMs(), elapsedMs(), safeZone())
      synchronized(submissionLock) {
        executor.execute {
          runCatching {
            startSession()
            reportHealth()
            val context = contextFields?.let { runCatching(it).getOrElse { mapOf("contextReadFailed" to true) } }.orEmpty()
            write(captured.copy(fields = captured.fields + sanitizeFields(context)))
          }.onFailure { failures.incrementAndGet() }
        }
      }
    } catch (_: Throwable) { dropped.incrementAndGet() }
  }

  fun snapshot(callback: (AlarmEventLog.Snapshot) -> Unit) {
    try {
      synchronized(submissionLock) {
        executor.execute {
          val result = try {
            startSession()
            reportHealth()
            val active = getStore()
            val bytes = active.snapshot()
            retainedBytes = bytes.size
            historyPartial = active.partial
            historyTruncated = active.historyTruncated
            recoveredIncompleteData = active.recoveredIncompleteData
            val status = status()
            val metadata = envelope(Captured("export.status", null, mapOf(
              "slotCount" to slotCount, "slotBytes" to slotBytes,
              "retentionBytes" to slotCount.toLong() * slotBytes,
              "droppedRecords" to status.droppedRecords, "writeFailures" to status.writeFailures,
              "retainedBytes" to status.retainedBytes, "queueDepth" to status.queueDepth,
              "partial" to status.partial,
              "historyTruncated" to status.historyTruncated,
              "recoveredIncompleteData" to status.recoveredIncompleteData,
            ), null, wallMs(), elapsedMs(), safeZone()))
            AlarmEventLog.Snapshot(bytes + metadata.toByteArray(Charsets.UTF_8), status)
          } catch (_: Throwable) {
            failures.incrementAndGet()
            AlarmEventLog.Snapshot(ByteArray(0), status("snapshot_read_failed"))
          }
          runCatching { callback(result) }
        }
      }
    } catch (_: Throwable) {
      dropped.incrementAndGet()
      runCatching { callback(AlarmEventLog.Snapshot(ByteArray(0), status("snapshot_queue_full"))) }
    }
  }

  fun flush(timeoutMs: Long) {
    if (Thread.currentThread() === writerThread) return
    val completed = CountDownLatch(1)
    runCatching {
      synchronized(submissionLock) { executor.execute { completed.countDown() } }
      completed.await(timeoutMs.coerceIn(0, 200), TimeUnit.MILLISECONDS)
    }
  }

  internal fun close() { executor.shutdown() }

  private fun status(failure: String? = null) = AlarmEventLog.Status(
    sessionId, dropped.get(), failures.get(), retainedBytes, executor.queue.size,
    historyPartial || dropped.get() > 0 || failures.get() > 0 || failure != null, failure,
    historyTruncated, recoveredIncompleteData,
  )

  private fun startSession() {
    if (sessionStarted) return
    val saved = write(Captured("process.session_start", null, sanitizeFields(runCatching(sessionFields).getOrElse {
      mapOf("sessionContextReadFailed" to true)
    }), null, wallMs(), elapsedMs(), safeZone()))
    if (!saved) return
    sessionStarted = true
    runCatching(previousExits).getOrDefault(emptyList()).take(3).forEach {
      write(Captured("process.previous_exit", null, sanitizeFields(it), null, wallMs(), elapsedMs(), safeZone()))
    }
  }

  private fun reportHealth() {
    val newDrops = dropped.get()
    val newFailures = failures.get()
    if (newDrops == reportedDrops && newFailures == reportedFailures) return
    write(Captured("logger.health", null, mapOf("droppedRecords" to newDrops, "writeFailures" to newFailures),
      null, wallMs(), elapsedMs(), safeZone()))
    reportedDrops = newDrops
    reportedFailures = newFailures
  }

  private fun write(captured: Captured): Boolean {
    return try {
      val active = getStore()
      active.append(envelope(captured).toByteArray(Charsets.UTF_8))
      retainedBytes = active.retainedBytes
      historyPartial = active.partial
      historyTruncated = active.historyTruncated
      recoveredIncompleteData = active.recoveredIncompleteData
      true
    } catch (_: Throwable) {
      // A failed append may have written part of its row. Reopening repairs
      // that tail before any later record can be concatenated to it.
      store = null
      failures.incrementAndGet()
      false
    }
  }

  private fun getStore(): AlarmLogStore = store ?: AlarmLogStore(directory(), slotCount, slotBytes, appendRow).also { store = it }

  private fun safeZone(): String = runCatching(zoneId).getOrDefault("UTC").let {
    runCatching { ZoneId.of(it).id }.getOrDefault("UTC")
  }

  private fun envelope(captured: Captured): String {
    val at = Instant.ofEpochMilli(captured.wall)
    return JSONObject().apply {
      put("schema", 1)
      put("event", captured.event)
      put("session", sessionId)
      put("process", processId)
      put("sequence", ++sequence)
      put("utc", at.toString())
      put("local", at.atZone(ZoneId.of(captured.zone)).toOffsetDateTime().toString())
      put("zone", captured.zone)
      put("elapsedMs", captured.elapsed)
      captured.occurrence?.let { put("occurrence", it) }
      put("fields", JSONObject(captured.fields))
      captured.error?.let { put("error", JSONObject(it)) }
    }.toString() + "\n"
  }

  private data class Captured(
    val event: String, val occurrence: String?, val fields: Map<String, Any?>,
    val error: Map<String, Any?>?, val wall: Long, val elapsed: Long, val zone: String,
  )
}

/** Slot headers recover chronology after restart; a partial final row is discarded before appending. */
internal class AlarmLogStore(
  private val directory: File,
  private val slotCount: Int,
  private val slotBytes: Int,
  private val appendRow: (File, ByteArray) -> Unit = ::appendAlarmLogRow,
) {
  private val slots = (0 until slotCount).map { File(directory, "events.$it.jsonl") }
  private var activeIndex = 0
  private var generation = 0L
  private var recovered = false
  val historyTruncated: Boolean get() = generation > slotCount
  val recoveredIncompleteData: Boolean get() = recovered
  val partial: Boolean get() = recovered
  val retainedBytes: Int get() = slots.sumOf { minOf(it.length(), slotBytes.toLong()).toInt() }

  init {
    require(slotCount > 0 && slotBytes >= 256)
    check(directory.isDirectory || directory.mkdirs()) { "Alarm log directory unavailable" }
    val valid = slots.mapIndexedNotNull { index, file ->
      if (!file.exists()) null else readGeneration(file)?.let { index to it }
    }
    valid.maxByOrNull { it.second }?.let { (index, value) -> activeIndex = index; generation = value }
    if (generation == 0L) resetSlot(0, 1)
    else {
      val active = slots[activeIndex]
      val bytes = readBounded(active, slotBytes + 1)
      val bounded = bytes.take(slotBytes).toByteArray()
      val complete = bounded.indexOfLast { it == '\n'.code.toByte() } + 1
      if (bytes.size > slotBytes || complete != bytes.size) {
        recovered = true
        RandomAccessFile(active, "rw").use { it.setLength(complete.toLong()) }
      }
    }
  }

  fun append(bytes: ByteArray) {
    require(bytes.size <= minOf(AlarmEventLog.MAX_RECORD_BYTES, slotBytes - 128)) { "Alarm log row too large" }
    if (slots[activeIndex].length() + bytes.size > slotBytes) resetSlot((activeIndex + 1) % slotCount, generation + 1)
    appendRow(slots[activeIndex], bytes)
  }

  fun snapshot(): ByteArray {
    val output = ByteArrayOutputStream(slotCount * slotBytes)
    slots.mapNotNull { file -> readGeneration(file)?.let { file to it } }.sortedBy { it.second }.forEach { (file, _) ->
      val bytes = readBounded(file, slotBytes + 1)
      if (bytes.size > slotBytes) recovered = true
      val bounded = bytes.take(slotBytes).toByteArray()
      val complete = bounded.indexOfLast { it == '\n'.code.toByte() } + 1
      if (complete != bounded.size) recovered = true
      bounded.copyOf(complete).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { row ->
        // A complete but corrupt row is not a trustworthy event. Preserve the
        // live slot, omit that row from export and report incomplete data.
        if (runCatching { JSONObject(row) }.isSuccess) {
          output.write((row + "\n").toByteArray(Charsets.UTF_8))
        } else recovered = true
      }
    }
    return output.toByteArray()
  }

  private fun readGeneration(file: File): Long? {
    if (!file.exists()) return null
    return runCatching {
      val head = readBounded(file, 128).toString(Charsets.UTF_8).substringBefore('\n')
      JSONObject(head).getLong("slotGeneration").takeIf { it > 0 }
    }.getOrElse { recovered = true; null }
  }

  private fun resetSlot(index: Int, value: Long) {
    FileOutputStream(slots[index], false).use {
      it.write((JSONObject().put("schema", 1).put("slotGeneration", value).toString() + "\n").toByteArray(Charsets.UTF_8))
      it.flush()
    }
    activeIndex = index
    generation = value
  }

  private fun readBounded(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
    val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
    val buffer = ByteArray(minOf(limit, 8 * 1024))
    while (output.size() < limit) {
      val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
      if (count < 0) break
      if (count == 0) break
      output.write(buffer, 0, count)
    }
    output.toByteArray()
  }
}

private fun appendAlarmLogRow(file: File, bytes: ByteArray) {
  FileOutputStream(file, true).use { it.write(bytes); it.flush() }
}

private val safeKey = Regex("[A-Za-z][A-Za-z0-9_]{0,47}")
private val safeToken = Regex("[A-Za-z0-9_.:+/-]{1,120}")
private val unsafeKey = Regex("(?:^|_)(?:label|name|title|note|message|description|url|uri|path|address|password|credential|authorization|cookie|header|secret|token)(?:$|_)", RegexOption.IGNORE_CASE)
private val uriOrPath = Regex("(?:[A-Za-z][A-Za-z0-9+.-]*://|content:|file:|[A-Za-z]:[\\\\/]|^/|\\\\\\\\)", RegexOption.IGNORE_CASE)

internal fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun safeEvent(event: String): String = event.takeIf { it.length <= 80 && it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) } ?: "logger.redacted_event"

internal fun sanitizeFields(fields: Map<String, Any?>): Map<String, Any?> = buildMap {
  fields.entries.take(40).forEach { (key, value) ->
    if (!safeKey.matches(key)) return@forEach
    val normalizedKey = key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    put(key, when {
      key != "errorCodeName" && unsafeKey.containsMatchIn(normalizedKey) -> "[redacted]"
      value == null || value is Boolean || value is Int || value is Long || value is Short || value is Byte -> value
      value is Float -> value.takeIf { it.isFinite() }
      value is Double -> value.takeIf { it.isFinite() }
      key.endsWith("Id", ignoreCase = true) && value is String -> fingerprint(value)
      value is String -> value.takeIf { safeToken.matches(it) && !uriOrPath.containsMatchIn(it) } ?: "[redacted]"
      value is List<*> -> value.take(12).map { if (it is Number || it is Boolean) it else "[redacted]" }
      else -> "[redacted]"
    })
  }
}

internal fun sanitizeError(error: Throwable): Map<String, Any?> {
  // Raw exception messages often contain stream URLs or document paths, and can
  // contain an alarm label. Class/cause/frame identities preserve actionable detail.
  val types = mutableListOf<String>()
  var current: Throwable? = error
  repeat(4) {
    val active = current ?: return@repeat
    types.add(active.javaClass.name.take(160))
    current = runCatching { active.cause }.getOrNull()?.takeUnless { it === active }
  }
  val frames = runCatching { error.stackTrace.take(6).map { "${it.className}.${it.methodName}:${it.lineNumber}".take(200) } }
    .getOrDefault(emptyList())
  return mapOf("types" to types, "frames" to frames, "messageOmitted" to true)
}
