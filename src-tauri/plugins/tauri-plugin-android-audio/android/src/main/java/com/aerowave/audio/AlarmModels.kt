package com.aerowave.audio

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface NativeAlarmSource {
  data class Station(val stationId: String) : NativeAlarmSource
  data class Folder(val path: String) : NativeAlarmSource
}

internal data class NativeAlarm(
  val id: String,
  val label: String,
  val hour: Int,
  val minute: Int,
  val days: List<Int>,
  val enabled: Boolean,
  val source: NativeAlarmSource,
  val volume: Float,
  val fadeSecs: Int,
  val snoozeMins: Int,
  val autoStopMins: Int,
  val autoSnoozes: Int,
)

internal data class NativeStation(val id: String, val name: String, val url: String)

internal data class ScheduledOccurrence(
  val alarmId: String,
  val occurrenceId: String,
  val atMs: Long,
  val snoozed: Boolean,
  val autoSnoozesUsed: Int = 0,
  val heldUri: String? = null,
  val heldTitle: String? = null,
  val heldFolder: String? = null,
  val heldKind: String? = null,
  val heldNote: String? = null,
  val heldIsHls: Boolean = false,
)

internal data class RingingRecord(
  val alarm: NativeAlarm,
  val occurrenceId: String,
  val trigger: String,
  val startedAtMs: Long,
  val startedElapsedMs: Long,
  val sourceKind: String = "tone",
  val title: String? = null,
  val note: String? = null,
  val autoSnoozesUsed: Int = 0,
  val sourceUri: String? = null,
  val sourceFolder: String? = null,
  val sourceIsHls: Boolean = false,
)

internal data class PersistedAlarmState(
  val initialized: Boolean = false,
  val revision: Long = 0,
  val alarms: List<NativeAlarm> = emptyList(),
  val stations: List<NativeStation> = emptyList(),
  val backupFolder: String? = null,
  val scheduled: Map<String, ScheduledOccurrence> = emptyMap(),
  val snoozes: Map<String, ScheduledOccurrence> = emptyMap(),
  val ringing: RingingRecord? = null,
  val error: String? = null,
)

internal fun NativeAlarm.toJson(): JSONObject = JSONObject().apply {
  put("id", id)
  put("label", label)
  put("hour", hour)
  put("minute", minute)
  put("days", JSONArray(days))
  put("enabled", enabled)
  put("source", when (val value = source) {
    is NativeAlarmSource.Station -> JSONObject().put("kind", "station").put("stationId", value.stationId)
    is NativeAlarmSource.Folder -> JSONObject().put("kind", "folder").put("path", value.path)
  })
  put("volume", volume.toDouble())
  put("fadeSecs", fadeSecs)
  put("snoozeMins", snoozeMins)
  put("autoStopMins", autoStopMins)
  put("autoSnoozes", autoSnoozes)
}

internal fun alarmFromJson(value: JSONObject): NativeAlarm {
  val id = value.getString("id").trim()
  require(id.isNotEmpty()) { "Alarm id cannot be empty" }
  val hour = value.getInt("hour")
  val minute = value.getInt("minute")
  require(hour in 0..23 && minute in 0..59) { "Alarm time is invalid" }
  val daysJson = value.optJSONArray("days") ?: JSONArray()
  val days = buildList {
    for (index in 0 until daysJson.length()) add(daysJson.getInt(index))
  }.distinct().sorted()
  require(days.all { it in 0..6 }) { "Alarm weekdays must be between 0 and 6" }
  val sourceJson = value.getJSONObject("source")
  val source = when (sourceJson.getString("kind")) {
    "station" -> NativeAlarmSource.Station(sourceJson.getString("stationId"))
    "folder" -> NativeAlarmSource.Folder(sourceJson.getString("path"))
    else -> throw IllegalArgumentException("Alarm source is invalid")
  }
  return NativeAlarm(
    id = id,
    label = value.optString("label", ""),
    hour = hour,
    minute = minute,
    days = days,
    enabled = value.optBoolean("enabled", true),
    source = source,
    volume = value.optDouble("volume", 0.8).toFloat().coerceIn(0f, 1f),
    fadeSecs = value.optInt("fadeSecs", 20).coerceAtLeast(0),
    snoozeMins = value.optInt("snoozeMins", 10).coerceAtLeast(1),
    autoStopMins = value.optInt("autoStopMins", 30).coerceAtLeast(0),
    autoSnoozes = value.optInt("autoSnoozes", 0).coerceAtLeast(0),
  )
}

internal fun NativeStation.toJson(): JSONObject = JSONObject()
  .put("id", id).put("name", name).put("url", url)

internal fun stationFromJson(value: JSONObject): NativeStation = NativeStation(
  value.getString("id"), value.optString("name", "Station"), value.getString("url"),
)

internal fun ScheduledOccurrence.toJson(): JSONObject = JSONObject()
  .put("alarmId", alarmId).put("occurrenceId", occurrenceId)
  .put("atMs", atMs).put("snoozed", snoozed).put("autoSnoozesUsed", autoSnoozesUsed)
  .put("heldUri", heldUri).put("heldTitle", heldTitle).put("heldFolder", heldFolder)
  .put("heldKind", heldKind).put("heldNote", heldNote)
  .put("heldIsHls", heldIsHls)

internal fun occurrenceFromJson(value: JSONObject) = ScheduledOccurrence(
  value.getString("alarmId"), value.getString("occurrenceId"),
  value.getLong("atMs"), value.optBoolean("snoozed", false),
  value.optInt("autoSnoozesUsed", 0),
  value.optNullableString("heldUri"),
  value.optNullableString("heldTitle"),
  value.optNullableString("heldFolder"),
  value.optNullableString("heldKind"),
  value.optNullableString("heldNote"),
  value.optBoolean("heldIsHls", false),
)

internal fun RingingRecord.toJson(): JSONObject = JSONObject()
  .put("alarm", alarm.toJson()).put("occurrenceId", occurrenceId)
  .put("trigger", trigger).put("startedAtMs", startedAtMs)
  .put("startedElapsedMs", startedElapsedMs)
  .put("sourceKind", sourceKind).put("title", title).put("note", note)
  .put("autoSnoozesUsed", autoSnoozesUsed)
  .put("sourceUri", sourceUri).put("sourceFolder", sourceFolder)
  .put("sourceIsHls", sourceIsHls)

internal fun ringingFromJson(value: JSONObject) = RingingRecord(
  alarm = alarmFromJson(value.getJSONObject("alarm")),
  occurrenceId = value.getString("occurrenceId"),
  trigger = value.getString("trigger"),
  startedAtMs = value.getLong("startedAtMs"),
  startedElapsedMs = value.optLong("startedElapsedMs", 0),
  sourceKind = value.optString("sourceKind", "tone"),
  title = value.optNullableString("title"),
  note = value.optNullableString("note"),
  autoSnoozesUsed = value.optInt("autoSnoozesUsed", 0),
  sourceUri = value.optNullableString("sourceUri"),
  sourceFolder = value.optNullableString("sourceFolder"),
  sourceIsHls = value.optBoolean("sourceIsHls", false),
)

internal fun JSONObject.optNullableString(name: String): String? =
  if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

internal fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> = buildList {
  for (index in 0 until length()) add(block(getJSONObject(index)))
}

internal fun JSONObject.objectMap(name: String): Map<String, ScheduledOccurrence> {
  val objectValue = optJSONObject(name) ?: return emptyMap()
  return buildMap {
    val keys = objectValue.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      put(key, occurrenceFromJson(objectValue.getJSONObject(key)))
    }
  }
}
