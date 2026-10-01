package com.aerowave.audio

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

internal object AlarmStateStore {
  private const val PREFS = "aerowave_android_alarms"
  private const val STATE = "state"
  private val lock = Any()
  private val rejectedValues = WeakHashMap<SharedPreferences, String?>()

  fun snapshot(context: Context): PersistedAlarmState = synchronized(lock) { read(context) }

  fun update(
    context: Context,
    transform: (PersistedAlarmState) -> PersistedAlarmState,
  ): PersistedAlarmState = updateBeforeCommit(context, transform = transform)

  /** Keep completion's OS timer and its durable occurrence under the same guard. */
  fun updateBeforeCommit(
    context: Context,
    beforeCommit: (PersistedAlarmState, PersistedAlarmState) -> Unit = { _, _ -> },
    onFailure: (PersistedAlarmState, PersistedAlarmState) -> Unit = { _, _ -> },
    transform: (PersistedAlarmState) -> PersistedAlarmState,
  ): PersistedAlarmState = synchronized(lock) {
    val preferences = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val previousRaw = raw(preferences)
    val before = decodeRaw(previousRaw)
    val changed = transform(before)
    if (changed == before) return@synchronized before
    var writeAttempted = false
    try {
      beforeCommit(before, changed)
      writeAttempted = true
      check(preferences.edit().putString(STATE, encode(changed).toString()).commit()) {
        "Android could not persist the alarm schedule"
      }
      rejectedValues.remove(preferences)
      changed
    } catch (error: Exception) {
      if (writeAttempted) {
        // commit(false) can already publish the rejected value to readers in
        // this process. Restore the previous bytes before releasing the lock.
        rejectedValues[preferences] = previousRaw
        try {
          check(preferences.edit().putString(STATE, previousRaw).commit()) {
            "Android could not restore the previous alarm schedule"
          }
          rejectedValues.remove(preferences)
        } catch (rollbackError: Exception) {
          error.addSuppressed(rollbackError)
          AlarmEventLog.record(context, "schedule.persistence_rollback_failed",
            before.ringing?.occurrenceId, error = rollbackError)
        }
      }
      try {
        onFailure(before, changed)
      } catch (rollbackError: Exception) {
        error.addSuppressed(rollbackError)
        AlarmEventLog.record(context, "schedule.completion_rollback_failed",
          before.ringing?.occurrenceId, error = rollbackError)
      }
      throw error
    }
  }

  private fun read(context: Context): PersistedAlarmState {
    val preferences = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return decodeRaw(raw(preferences))
  }

  private fun raw(preferences: SharedPreferences): String? =
    if (rejectedValues.containsKey(preferences)) rejectedValues[preferences]
    else preferences.getString(STATE, null)

  private fun decodeRaw(raw: String?): PersistedAlarmState {
    if (raw == null) return PersistedAlarmState()
    return try {
      decode(JSONObject(raw))
    } catch (error: Exception) {
      PersistedAlarmState(error = "Saved Android alarm state could not be read: ${error.message}")
    }
  }

  // Alarm definitions must be readable after LOCKED_BOOT_COMPLETED so the
  // next AlarmClock can be restored before the first unlock.
  private fun storageContext(context: Context): Context =
    context.createDeviceProtectedStorageContext()

  private fun encode(state: PersistedAlarmState): JSONObject = JSONObject().apply {
    put("initialized", state.initialized)
    put("revision", state.revision)
    put("alarms", JSONArray().also { array -> state.alarms.forEach { array.put(it.toJson()) } })
    put("stations", JSONArray().also { array -> state.stations.forEach { array.put(it.toJson()) } })
    put("backupFolder", state.backupFolder)
    put("scheduled", JSONObject().also { out -> state.scheduled.forEach { (id, value) -> out.put(id, value.toJson()) } })
    put("snoozes", JSONObject().also { out -> state.snoozes.forEach { (id, value) -> out.put(id, value.toJson()) } })
    put("ringing", state.ringing?.toJson())
    put("error", state.error)
  }

  private fun decode(value: JSONObject) = PersistedAlarmState(
    initialized = value.optBoolean("initialized", false),
    revision = value.optLong("revision", 0),
    alarms = (value.optJSONArray("alarms") ?: JSONArray()).mapObjects(::alarmFromJson),
    stations = (value.optJSONArray("stations") ?: JSONArray()).mapObjects(::stationFromJson),
    backupFolder = value.optNullableString("backupFolder"),
    scheduled = value.objectMap("scheduled"),
    snoozes = value.objectMap("snoozes"),
    ringing = value.optJSONObject("ringing")?.let(::ringingFromJson),
    error = value.optNullableString("error"),
  )
}
