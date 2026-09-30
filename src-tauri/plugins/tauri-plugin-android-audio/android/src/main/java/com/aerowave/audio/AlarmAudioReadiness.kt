package com.aerowave.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.tauri.plugin.JSObject
import org.json.JSONArray
import org.json.JSONObject

/** Read-only media volume and available sinks, not a claim about player routing. */
internal object AlarmAudioReadiness {
  fun snapshot(context: Context): JSObject {
    val manager = context.getSystemService(AudioManager::class.java)
    return JSObject().apply {
      put("mediaVolume", safely { manager.getStreamVolume(AudioManager.STREAM_MUSIC) } ?: JSONObject.NULL)
      put("mediaVolumeMax", safely { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } ?: JSONObject.NULL)
      put("mediaMuted", safely { manager.isStreamMute(AudioManager.STREAM_MUSIC) } ?: JSONObject.NULL)
      put("outputs", safely {
        JSONArray().apply {
          manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            put(JSObject().apply {
              put("name", device.productName?.toString()?.take(120).orEmpty())
              put("kind", kind(device.type))
              put("bluetooth", bluetooth(device.type))
            })
          }
        }
      } ?: JSONObject.NULL)
    }
  }

  internal fun bluetooth(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> true
    else -> false
  }

  internal fun kind(type: Int): String = when {
    bluetooth(type) -> "Bluetooth audio"
    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
    type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
    type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE ||
      type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
    type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC ||
      type == AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI audio"
    else -> "Audio output"
  }

  private inline fun <T> safely(block: () -> T): T? = try {
    block()
  } catch (_: Exception) {
    null
  }
}
