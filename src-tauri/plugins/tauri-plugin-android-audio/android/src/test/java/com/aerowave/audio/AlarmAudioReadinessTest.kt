package com.aerowave.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmAudioReadinessTest {
  @Test fun readsMediaVolumeWithoutChangingIt() {
    val context = RuntimeEnvironment.getApplication()
    val manager = context.getSystemService(AudioManager::class.java)
    manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    val muted = AlarmAudioReadiness.snapshot(context)
    assertEquals(0, muted.getInt("mediaVolume"))
    assertEquals(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), muted.getInt("mediaVolumeMax"))
    manager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
    val audible = AlarmAudioReadiness.snapshot(context)
    assertEquals(3, audible.getInt("mediaVolume"))
    assertEquals(3, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(audible.has("outputs"))
    assertFalse(audible.has("activeRoute"))
  }

  @Test fun identifiesBluetoothSinksWithoutLookingAtBondedDevices() {
    for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
      AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID)) {
      assertTrue(AlarmAudioReadiness.bluetooth(type))
    }
    assertFalse(AlarmAudioReadiness.bluetooth(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    assertFalse(AlarmAudioReadiness.bluetooth(AudioDeviceInfo.TYPE_USB_HEADSET))
    assertEquals("Phone speaker", AlarmAudioReadiness.kind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    assertEquals("Audio output", AlarmAudioReadiness.kind(AudioDeviceInfo.TYPE_UNKNOWN))
  }
}
