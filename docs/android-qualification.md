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
| Same physical phone, optimized signed ARM64 release | Restored settings and folder grant, granted alarm and notification permissions, audible radio, disabled WebView inspection, and the ongoing locked-screen playback run |
| Android API 36 x86_64 emulator, debug and signed release builds | Fresh permission defaults, reboot restoration, locked wake and notification control flow, fallback selection, large-text layout, and signed replacement preserving data |
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

### Temporary audio interruptions on the signed release

The installed optimized 0.11.5 release was checked with a separate, silent
helper requesting Android's
[temporary audio focus](https://developer.android.com/media/optimize/audio-focus).
The helper played no audio and released each focus request after 15 seconds.

- `AUDIOFOCUS_GAIN_TRANSIENT` paused the radio's native renderer. Playback
  resumed automatically when focus returned, at the previous volume. The tester
  heard both the pause and the automatic recovery without pressing Play.
- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` kept the renderer active. Android listed
  the owned player as ducked during the request and removed that attenuation
  afterward. The tester heard the volume decrease and return without a stop.
- The pause/resume sequence also passed while Android reported the screen
  asleep before, during and after the interruption.
- An explicit media Pause during temporary focus loss kept the renderer paused
  after focus returned. A subsequent explicit Play restored the station.

These checks establish temporary focus handling on this device and build.
They do not reproduce a cellular call or every assistant/navigation app's
routing behavior. The earlier other-music-app test covers permanent focus loss.

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

## Signed packaging

Optimized version 0.11.5 (Android version code 11005) was built for ARM64 and
x86_64 from clean source commit `bbc6e7d`. Both APKs contain only their requested
native ABI and passed signature verification against the same persistent release
certificate.
The ARM64 package also passed `zipalign -c -P 16 4`.

| Artifact | APK SHA-256 |
|---|---|
| ARM64 | `A5DE2DFF48C342B69308DE830FBDA361CAD9FA7AAA08BA358A24418057D0BFB4` |
| x86_64 | `B7D1DF66852AC2F5D1F47777A53EA4C4E87EF6F36B8E940DE300B93D685BAECB` |

The signed x86_64 build installed on a fresh emulator. Its minified command
bridge saved a station and alarm. Replacing it with the same certificate kept
both records, the first-install timestamp and the private data directory.
The final package is non-debuggable and its bytecode explicitly disables
WebView inspection. The Google APIs `userdebug` image nevertheless exposes
inspection even after that call. The production phone's `user` image reported
no WebView debugging socket for the installed release process.

The tester approved the one-time preview uninstall after the app's private
data was backed up. The saved station and settings were restored, the piano
folder's access was granted again, and the optimized ARM64 release replaced
the migration build without another uninstall. The installed APK's SHA-256
matches the final artifact above. Its package is non-debuggable, and exact-alarm
and notification access remained granted. The tester heard the saved station
through the installed release, then muted the app and locked the phone. Android
reported an active renderer with the screen asleep.

## Extended playback run

A bounded eight-hour run started on the physical phone at 14:21 UTC on
2026-09-19, with app playback muted and the phone charging. It was stopped to
prepare the requested signed-release installation. Its retained samples show
an active renderer without detected interruptions across 20 samples spanning
19 minutes; this is not an eight-hour pass.

A new eight-hour run of the installed signed release started at 14:54 UTC on
2026-09-19, after the tester confirmed audible radio and then muted and locked
the phone. It was intentionally stopped at 14:59 UTC for the requested temporary
focus checks and a reported release folder-picker defect. Six samples spanning
five minutes showed an active renderer without detected interruptions. A fresh
long run is needed after the corrected release is installed. The monitor records
actual screen state and filtered renderer evidence.
The one-minute monitor validation completed with six active-renderer samples
and no detected issues. Unknown MediaSession positions were correctly treated
as unavailable telemetry.

## Qualification still open

The Android scope is not complete or release-qualified. The remaining gates
include:

- overnight playback and alarm delivery under the target phone's battery
  policy;
- a complete long-duration run of the installed signed release.
