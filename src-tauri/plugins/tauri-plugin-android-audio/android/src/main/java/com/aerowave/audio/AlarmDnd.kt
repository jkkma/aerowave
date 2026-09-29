package com.aerowave.audio

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.tauri.plugin.JSObject
import org.json.JSONObject

/** A read-only view of the effective DND policy and the ringing channel. */
internal object AlarmDnd {
  fun snapshot(context: Context): JSObject {
    val manager = context.getSystemService(NotificationManager::class.java)
    val filter = safely { manager.currentInterruptionFilter }
    val active = activeForFilter(filter)
    val access = safely { manager.isNotificationPolicyAccessGranted }?.let {
      if (it) "granted" else "denied"
    } ?: "unknown"
    val policy = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
        safely { manager.consolidatedNotificationPolicy }
      access == "granted" -> safely { manager.notificationPolicy }
      else -> null
    }
    val alarmBypass = safely {
      manager.getNotificationChannel(AlarmPlaybackService.CHANNEL_ID)?.canBypassDnd()
    }
    val categories = policy?.priorityCategories
    val alarmsAllowed = when {
      active == false -> true
      filter == NotificationManager.INTERRUPTION_FILTER_NONE -> false
      filter == NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
      categories != null ->
        categories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
      else -> null
    }
    val mediaAllowed = when {
      active == false -> true
      filter == NotificationManager.INTERRUPTION_FILTER_NONE -> false
      filter == NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
      categories != null ->
        categories and NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA != 0
      else -> null
    }
    val fullScreenSuppressed = when {
      active == false -> false
      active != true || policy == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> null
      // This applies only if Android first intercepts this channel's notification.
      else -> policy.suppressedVisualEffects and
        NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT != 0
    }
    return JSObject().apply {
      putNullableBoolean("active", active)
      put("access", access)
      putNullableBoolean("alarmBypass", alarmBypass)
      putNullableBoolean("fullScreenSuppressed", fullScreenSuppressed)
      putNullableBoolean("alarmsAllowed", alarmsAllowed)
      putNullableBoolean("mediaAllowed", mediaAllowed)
    }
  }

  internal fun shouldRequestAlarmScreen(
    manufacturer: String,
    brand: String,
    trigger: String,
    dndActive: Boolean?,
    interactive: Boolean?,
    notificationsEnabled: Boolean,
    channelImportance: Int?,
    fullScreenAllowed: Boolean,
    overlayAllowed: Boolean,
  ): Boolean =
    (manufacturer.equals("Xiaomi", ignoreCase = true) ||
      brand.equals("POCO", ignoreCase = true) ||
      brand.equals("Redmi", ignoreCase = true) ||
      brand.equals("Xiaomi", ignoreCase = true)) &&
      trigger != "test" && dndActive == true && interactive == false &&
      notificationsEnabled &&
      channelImportance != null && channelImportance >= NotificationManager.IMPORTANCE_HIGH &&
      fullScreenAllowed && overlayAllowed

  internal fun active(context: Context): Boolean? = activeForFilter(safely {
    context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
  })

  private fun activeForFilter(filter: Int?): Boolean? = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_ALL -> false
    NotificationManager.INTERRUPTION_FILTER_PRIORITY,
    NotificationManager.INTERRUPTION_FILTER_ALARMS,
    NotificationManager.INTERRUPTION_FILTER_NONE -> true
    else -> null
  }

  private fun JSObject.putNullableBoolean(name: String, value: Boolean?) {
    put(name, value ?: JSONObject.NULL)
  }

  private inline fun <T> safely(block: () -> T): T? = try {
    block()
  } catch (_: Exception) {
    null
  }
}
