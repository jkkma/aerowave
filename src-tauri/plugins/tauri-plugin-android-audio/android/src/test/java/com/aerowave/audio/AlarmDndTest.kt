package com.aerowave.audio

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmDndTest {
  private val context get() = RuntimeEnvironment.getApplication()

  @Test fun snapshotReportsEffectivePolicyWithoutDndAccessAndRefreshesAfterRevocation() {
    val manager = context.getSystemService(NotificationManager::class.java)
    val shadow = Shadows.shadowOf(manager)
    manager.createNotificationChannel(NotificationChannel(
      AlarmPlaybackService.CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH,
    ))
    shadow.setNotificationPolicyAccessGranted(true)
    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    shadow.setConsolidatedNotificationPolicy(NotificationManager.Policy(
      NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
        NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA,
      NotificationManager.Policy.PRIORITY_SENDERS_ANY,
      NotificationManager.Policy.PRIORITY_SENDERS_ANY,
      NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT,
    ))
    val granted = AlarmDnd.snapshot(context)
    assertEquals("granted", granted.getString("access"))
    assertTrue(granted.getBoolean("active"))
    assertFalse(granted.getBoolean("alarmBypass"))
    assertTrue(granted.getBoolean("fullScreenSuppressed"))
    assertTrue(granted.getBoolean("alarmsAllowed"))
    assertTrue(granted.getBoolean("mediaAllowed"))

    shadow.setNotificationPolicyAccessGranted(false)
    val revoked = AlarmDnd.snapshot(context)
    assertEquals("denied", revoked.getString("access"))
    assertTrue(revoked.getBoolean("active"))
    assertFalse(revoked.getBoolean("alarmBypass"))
    assertTrue(revoked.getBoolean("fullScreenSuppressed"))
  }

  @Test fun alarmsOnlyFilterKeepsAlarmAndMediaAudibleRegardlessOfPriorityBits() {
    val manager = context.getSystemService(NotificationManager::class.java)
    val shadow = Shadows.shadowOf(manager)
    shadow.setNotificationPolicyAccessGranted(true)
    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    shadow.setConsolidatedNotificationPolicy(NotificationManager.Policy(
      0, NotificationManager.Policy.PRIORITY_SENDERS_ANY,
      NotificationManager.Policy.PRIORITY_SENDERS_ANY, 0,
    ))
    val snapshot = AlarmDnd.snapshot(context)
    assertTrue(snapshot.getBoolean("active"))
    assertTrue(snapshot.getBoolean("alarmsAllowed"))
    assertTrue(snapshot.getBoolean("mediaAllowed"))
  }

  @Test fun screenFallbackRequiresRealClaimDndScreenOffAndNotificationAccess() {
    fun eligible(
      manufacturer: String = "Xiaomi",
      trigger: String = "scheduled",
      active: Boolean? = true,
      interactive: Boolean? = false,
      notifications: Boolean = true,
      importance: Int? = NotificationManager.IMPORTANCE_HIGH,
      fullScreen: Boolean = true,
      overlay: Boolean = true,
    ) = AlarmDnd.shouldRequestAlarmScreen(
      manufacturer, "POCO", trigger, active, interactive,
      notifications, importance, fullScreen, overlay,
    )
    assertTrue(eligible())
    assertTrue(eligible(trigger = "snooze"))
    assertFalse(eligible(trigger = "test"))
    assertFalse(eligible(active = false))
    assertFalse(eligible(active = null))
    assertFalse(eligible(interactive = true))
    assertFalse(eligible(interactive = null))
    assertFalse(eligible(notifications = false))
    assertFalse(eligible(importance = NotificationManager.IMPORTANCE_DEFAULT))
    assertFalse(eligible(importance = null))
    assertFalse(eligible(fullScreen = false))
    assertFalse(eligible(overlay = false))
    assertFalse(AlarmDnd.shouldRequestAlarmScreen(
      "Samsung", "Samsung", "scheduled", true, false, true,
      NotificationManager.IMPORTANCE_HIGH, true, true,
    ))
  }

  @Test fun overlayAccessReflectsGrantAndRevocation() {
    ShadowSettings.setCanDrawOverlays(false)
    assertEquals("denied", alarmScreenOverlayAccess(context))
    ShadowSettings.setCanDrawOverlays(true)
    assertEquals("granted", alarmScreenOverlayAccess(context))
    ShadowSettings.setCanDrawOverlays(false)
    assertEquals("denied", alarmScreenOverlayAccess(context))
  }

  @Test fun ringingServiceRequestsExistingActivityOnceAndPreparationDoesNotWakeIt() {
    val originalManufacturer = Build.MANUFACTURER
    val originalBrand = Build.BRAND
    val originalOverlay = Settings.canDrawOverlays(context)
    ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
    ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "POCO")
    try {
      val manager = context.getSystemService(NotificationManager::class.java)
      Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(true)
      manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
      Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
      Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      ShadowSettings.setCanDrawOverlays(true)
      val current = ring()
      AlarmStateStore.update(context) { PersistedAlarmState(ringing = current) }
      val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
      val service = controller.get()
      service.onStartCommand(
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, current.occurrenceId),
        0, 1,
      )
      val launch = Shadows.shadowOf(context).nextStartedActivity
      assertEquals(AlarmActivity::class.java.name, launch?.component?.className)
      assertEquals(current.occurrenceId, launch?.getStringExtra("occurrenceId"))
      service.onStartCommand(
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, current.occurrenceId),
        0, 2,
      )
      service.onStartCommand(
        AlarmPlaybackService.prepareIntentFor(
          context, current.alarm.id, current.occurrenceId, current.startedAtMs, false,
        ), 0, 3,
      )
      assertNull(Shadows.shadowOf(context).nextStartedActivity)
      assertFalse(manager.getNotificationChannel("aerowave_alarm_preparation_v1").canBypassDnd())
      controller.destroy()
    } finally {
      ShadowSettings.setCanDrawOverlays(originalOverlay)
      ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
      ReflectionHelpers.setStaticField(Build::class.java, "BRAND", originalBrand)
    }
  }

  @Test fun deniedOverlayAccessLeavesTheRingingNotificationWithoutDirectActivity() {
    val originalManufacturer = Build.MANUFACTURER
    val originalBrand = Build.BRAND
    val originalOverlay = Settings.canDrawOverlays(context)
    ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
    ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "POCO")
    try {
      ShadowSettings.setCanDrawOverlays(false)
      val notifications = context.getSystemService(NotificationManager::class.java)
      Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true)
      notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
      Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
      Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      val current = ring()
      AlarmStateStore.update(context) { PersistedAlarmState(ringing = current) }
      val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
      controller.get().onStartCommand(
        AlarmPlaybackService.intentFor(context, AlarmPlaybackService.ACTION_RING, current.occurrenceId),
        0, 1,
      )
      assertEquals(AlarmPlaybackService.NOTIFICATION_ID,
        Shadows.shadowOf(controller.get()).lastForegroundNotificationId)
      assertNull(Shadows.shadowOf(context).nextStartedActivity)
      controller.destroy()
    } finally {
      ShadowSettings.setCanDrawOverlays(originalOverlay)
      ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
      ReflectionHelpers.setStaticField(Build::class.java, "BRAND", originalBrand)
    }
  }

  @Test fun silentStationPreparationNeverLaunchesTheAlarmScreen() {
    val originalManufacturer = Build.MANUFACTURER
    val originalBrand = Build.BRAND
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val originalExact = alarmManager.canScheduleExactAlarms()
    ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
    ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "POCO")
    try {
      ShadowAlarmManager.setCanScheduleExactAlarms(true)
      val notifications = context.getSystemService(NotificationManager::class.java)
      Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true)
      notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
      Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
      Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
      val due = System.currentTimeMillis() + 30_000L
      val alarm = ring().alarm.copy(source = NativeAlarmSource.Station("radio"))
      val scheduled = ScheduledOccurrence(
        alarmId = alarm.id, occurrenceId = "wake:soon", atMs = due, snoozed = false,
        heldUri = "content://missing/prepared", heldKind = "station",
      )
      AlarmStateStore.update(context) {
        PersistedAlarmState(
          alarms = listOf(alarm),
          stations = listOf(NativeStation("radio", "Radio", "https://example.org/radio")),
          scheduled = mapOf(alarm.id to scheduled),
        )
      }
      val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
      controller.get().onStartCommand(
        AlarmPlaybackService.prepareIntentFor(context, alarm.id, scheduled.occurrenceId, due, false),
        0, 1,
      )
      assertEquals(71_002, Shadows.shadowOf(controller.get()).lastForegroundNotificationId)
      assertNull(Shadows.shadowOf(context).nextStartedActivity)
      assertFalse(notifications.getNotificationChannel("aerowave_alarm_preparation_v1").canBypassDnd())
      controller.destroy()
    } finally {
      ShadowAlarmManager.setCanScheduleExactAlarms(originalExact)
      ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
      ReflectionHelpers.setStaticField(Build::class.java, "BRAND", originalBrand)
    }
  }

  @Test fun settingsIntentsOpenDndControlsAndOnlyTheRingingChannel() {
    assertEquals(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
      alarmSettingsIntent(context, "dndSettings").action)
    val channel = alarmSettingsIntent(context, "alarmChannel")
    assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
    assertEquals(context.packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    assertEquals(AlarmPlaybackService.CHANNEL_ID,
      channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    val overlay = alarmSettingsIntent(context, "alarmScreen")
    assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
    assertEquals("package:${context.packageName}", overlay.dataString)
  }

  private fun ring() = RingingRecord(
    alarm = NativeAlarm(
      id = "wake", label = "Wake", hour = 7, minute = 0,
      days = emptyList(), enabled = true,
      source = NativeAlarmSource.Folder("content://missing"),
      volume = 0.5f, fadeSecs = 0, snoozeMins = 10,
      autoStopMins = 0, autoSnoozes = 0,
    ),
    occurrenceId = "wake:claimed", trigger = "scheduled",
    startedAtMs = System.currentTimeMillis(), startedElapsedMs = 1_000L,
    sourceUri = "content://missing/audio",
  )
}
