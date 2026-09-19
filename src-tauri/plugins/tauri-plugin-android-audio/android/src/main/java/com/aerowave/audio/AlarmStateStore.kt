package com.aerowave.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object AlarmStateStore {
  private const val PREFS = "aerowave_android_alarms"
  private const val STATE = "state"
  private val lock = Any()

  fun snapshot(context: Context): PersistedAlarmState = synchronized(lock) { read(context) }

  fun update(
    context: Context,
    transform: (PersistedAlarmState) -> PersistedAlarmState,
  ): PersistedAlarmState = synchronized(lock) {
    val changed = transform(read(context))
    val saved = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(STATE, encode(changed).toString()).commit()
    check(saved) { "Android could not persist the alarm schedule" }
    changed
  }

  private fun read(context: Context): PersistedAlarmState {
    val raw = storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STATE, null)
      ?: return PersistedAlarmState()
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
