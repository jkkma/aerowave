# Android qualification record — 2026-09-19

This record summarizes the Android checks retained on 2026-09-19. It is an
evidence snapshot, not final release qualification. The raw device artifacts
are retained separately; this public record omits device identifiers, local
paths and personal information.

For the feature boundaries and test procedure, see [Android](android.md). For
signing and retained release artifacts, see [Android releases](android-release.md).

## Test environments

| Environment | What it establishes |
|---|---|
| POCO X3 Pro, Android 13, ARM64 debug build | Audible playback, physical audio routes, persisted folder access, locked-screen playback, exact alarm timing and native sleep-timer behavior |
| Android API 36 x86_64 emulator, debug build | Fresh permission defaults, reboot restoration, locked wake and notification control flow, fallback selection, and large-text layout |
| Automated suites | Frontend, Rust core and Android JVM regression behavior |

The emulator did not provide usable audio output. Its results therefore prove
state and control flow only; they do not prove decoding or audibility.

## Physical-device results

### Audio routes and folders

- AUX playback was audible. Unplugging the cable changed native playback to
  paused.
- Bluetooth playback was audible. Disconnecting the device paused playback;
  reconnecting did not start audio by itself, and manual resume played again.
- Another audio app took focus and paused Aerowave. Returning to Aerowave and
  pressing Play restored the radio, confirmed by the tester and native state.
- A folder selected through Android's system picker retained its read grant.
  Local playback opened the granted files and advanced to another track.
- Folder playback continued while the phone was locked and the webview was
  frozen, with native playback state still advancing.
- Turning off Wi-Fi and mobile data exposed a retry defect after stream end.
  With the fix installed, the player made four fresh connection attempts and
  reached the downloaded piano backup about 16 seconds after the stream ended.
  The tester heard the backup offline; native state showed continued local
  playback after Wi-Fi returned.

### Alarms

- A locked one-shot folder alarm entered the ringing state 54 ms after its
  requested wall-clock time, disabled the completed one-shot, and was heard by
  the tester.
- Manual snooze created a new exact occurrence. It returned 26 ms after the
  snooze target and the tester heard the held alarm track again.
- Notification Dismiss exposed a callback sequencing defect: an absent
  callback also skipped stopping the alarm. The corrected build received the
  tester's notification action and cleared ringing immediately, before its
  automatic stop deadline. The tester confirmed that the piano stopped.
- An outdated alarm save was rejected without changing the native revision
  or definitions, protecting native one-shot and snooze decisions from stale
  page state.
- Queued edits carry the revision from their preceding successful write,
  including station and folder metadata saves. Regression tests cover rapid
  toggles and rejection of the remaining queue after a native conflict. A
  metadata save followed immediately by enable and disable also passed through
  the actual phone bridge, preserving the final disabled state.
- An unavailable station selected the downloaded piano backup and paused the
  playing radio. Android reported an active alarm audio track. With the webview
  frozen, the one-minute automatic stop cleared ringing, removed the alarm
  service and restored the previous radio at its original playback volume.

### Sleep timer

- Replacing a playing station kept the existing timer and its original expiry
  instead of starting a new countdown.
- Cancelling during the fade, with 14,761 ms remaining, removed the timer and
  restored playback volume. Playback remained active beyond the old deadline.
- A timer continued while playback was paused. At expiry the native session
  became idle, and a later Resume command remained idle rather than restarting
  audio.

## API 36 emulator results

- A fresh install reported exact-alarm and notification access denied and
  battery optimization enabled. The app exposed those states. After the two
  permissions were granted, native alarm state reported them as granted.
- A scheduled one-shot survived a cold reboot, was restored before the webview
  opened, woke the locked emulator, entered its foreground alarm service and
  exposed its notification controls. Snooze updated the durable next
  occurrence.
- Missing-source tests reached the native fallback control path and selected
  the system alarm tone. Because the emulator had no working audio output,
  this does not establish that the tone decoded or was audible.
- Notification Snooze, re-ringing one minute later and notification Dismiss
  passed on the corrected native build. Revoking and granting notification
  access updated the app's permission state.
- At 1.30 and 2.00 system font scales, geometry probes verified the corrected
  alarm layout in portrait and landscape. The final packaged debug build also
  passed at 2.00 without injected styles: portrait had no horizontal overflow,
  and the landscape editor used the remaining viewport above the keyboard.
  The focused label stayed visible and Save remained reachable by scrolling.
- Forced deep idle could not be established while the imminent exact alarm
  was scheduled. That attempt does not qualify alarm delivery during Doze.

## Automated regression record

The retained run passed:

- 132 frontend tests;
- 128 Rust core tests;
- 39 Android JVM tests.

The Windows workspace also passed `cargo check --workspace --locked --offline`.

These suites cover deterministic state and boundary logic. They do not replace
device evidence for audio routing, OEM background policy, wake behavior or
audibility.

## Qualification still open

The Android scope is not complete or release-qualified. The remaining gates
include:

- transient audio-focus interruption checks;
- overnight playback and alarm delivery under the target phone's battery
  policy;
- final optimized, signed release build, install and replacement checks from
  [Android releases](android-release.md).
