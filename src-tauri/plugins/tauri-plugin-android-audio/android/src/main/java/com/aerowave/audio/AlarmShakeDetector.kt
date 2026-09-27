/*
 * Motion-strength detection adapted to Kotlin from LineageOS DeskClock's
 * AlarmService.java (f8d2258e6a673c1b576f009eec98781a5956ffdb):
 * https://github.com/LineageOS/android_packages_apps_DeskClock/blob/f8d2258e6a673c1b576f009eec98781a5956ffdb/src/com/android/deskclock/alarms/AlarmService.java
 * Full attribution and license are bundled in assets/licenses/lineageos-deskclock.txt.
 * That source bears:
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aerowave.audio

import kotlin.math.abs

/** Accumulate DeskClock's six gravity-free motion samples before checking on the next event. */
internal class AlarmShakeDetector {
  private var motionSum = 0f
  private var sampleCount = 0

  fun reset() {
    motionSum = 0f
    sampleCount = 0
  }

  fun sample(x: Float, y: Float, z: Float): Boolean {
    // DeskClock's `fill <= BUFFER` collects six samples for BUFFER = 5,
    // then checks their sum / 5 on the seventh event. Retain that calibration.
    if (sampleCount < WINDOW_SAMPLES) {
      motionSum += abs(x) + abs(y) + abs(z)
      sampleCount++
      return false
    }

    val shaken = motionSum >= MOTION_THRESHOLD * BUFFER
    reset()
    return shaken
  }

  companion object {
    private const val BUFFER = 5
    private const val WINDOW_SAMPLES = BUFFER + 1
    private const val MOTION_THRESHOLD = 16f
  }
}
