package com.aerowave.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

internal interface SystemAlarmTone {
  fun play()
  fun stop()
  fun isPlaying(): Boolean
  fun setVolume(volume: Float)
}

/** Default ringtone URIs may be playable only through Ringtone on newer Android builds. */
internal fun openSystemAlarmTone(context: Context, uri: Uri): SystemAlarmTone? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
  val ringtone = RingtoneManager.getRingtone(context, uri) ?: return null
  ringtone.setAudioAttributes(
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build(),
  )
  ringtone.setLooping(true)
  return PlatformSystemAlarmTone(ringtone)
}

@RequiresApi(Build.VERSION_CODES.P)
private class PlatformSystemAlarmTone(private val ringtone: Ringtone) : SystemAlarmTone {
  override fun play() = ringtone.play()
  override fun stop() = ringtone.stop()
  override fun isPlaying(): Boolean = ringtone.isPlaying
  override fun setVolume(volume: Float) = ringtone.setVolume(volume)
}
