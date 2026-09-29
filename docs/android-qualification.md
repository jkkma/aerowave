# Android qualification record

The initial checks below were retained on 2026-09-19; later checks are dated
separately. This is an evidence record, not final release qualification. Raw artifacts
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

### Folder response correction in 0.11.6

The optimized 0.11.5 release exposed a folder-picker error that had not occurred
in the debug build: `failed to deserialize response: missing field 'path'`.
Release minification renamed the Kotlin folder and track fields before their
reflective serialization. Version 0.11.6 constructs explicit response objects
for folder selection, folder information and random-track selection, preserving
the Rust bridge's required keys while retaining optimization.

The corrected build passed 41 Android JVM tests, including two new production
response-schema checks, plus 128 Rust core tests and the Windows workspace
check. The optimized ARM64 APK was built from clean source `9e32c2a`, verified
against the existing release certificate, and passed 16 KB ZIP alignment.
Its SHA-256 is
`935C8AD8E42EA012C7DF30F3D81197FC040EEE3B6DA447E6EA061AECA58C73C5`.
The same-certificate update installed as version code 11006 without uninstalling
the app, retained its first-install time and granted alarm/notification access,
and matched that hash when read back from the phone. WebView inspection remained
disabled. In that installed optimized build, the tester selected the backup
folder again and confirmed its one-file count without an error. Shuffle then
played the downloaded piano, confirmed by the tester, native track metadata and
an active audio renderer. This exercises the actual release bridge for both
folder and track responses.

### Bluetooth alarm and chained Opus correction in 0.11.7

An alarm using ChillSynth's Ogg Opus broadcast reproduced two separate faults
on the POCO X3 Pro. Android routed `USAGE_ALARM` to both the handset speaker and
Bluetooth A2DP. At a later song boundary, Media3 1.11.1 sent the next Ogg link's
`OpusHead` and `OpusTags` packets to the audio decoder, which rejected them and
caused the alarm to switch to its local backup.

Version 0.11.7 uses media routing with service-owned transient audio focus for
alarm sources. The tester confirmed Bluetooth-only sound with this correction,
and the active renderer used A2DP without the handset output. The alarm editor
explains the resulting media-volume and Do Not Disturb requirements. Bluetooth
disconnect no longer asks Media3 to pause an alarm; that disconnect path still
needs a separate physical-device check.

Both native players now use a narrowly scoped adapter for non-seekable chained
Opus with identical headers and a 3,840-sample pre-skip. It suppresses the link
headers, requests a decoder reset and corrects the header/pre-roll timestamps.
Eleven regression tests include a synthetic two-link stream through Media3's
real Ogg extractor, byte preservation, cumulative timing, seek reset, unchanged
Vorbis/seekable behavior and rejection of incompatible configurations. All 52
Android tests passed; the 128 Rust core tests, 132 frontend tests, Windows
workspace check and repository seam/version checks also passed.

The optimized ARM64 APK was built from local changes above `d9b43bd`, signed
with the existing release certificate and installed without uninstalling. It
passed 16 KB ZIP alignment, retained the original first-install time, and its
installed SHA-256 matched the retained artifact:
`61C80ED5E175656128D8A5571EF2D4F4B59796026C50EE099CE7A533E11ECD5F`.

The installed build's four-minute ChillSynth alarm test crossed two real link
boundaries. The adapter recorded both suppressed header pairs; Media3 recreated
the Opus decoder while keeping the same active AudioTrack and Bluetooth route.
Renderer frames continued increasing without an underrun, decoder error or
local-audio fallback.
The test was dismissed, and reopening the alarm confirmed its saved two-minute
give-up setting was unchanged. This validates the foreground alarm Test path on
this phone, not a new locked scheduled-start or overnight run. Recheck
decoder-reset behavior when changing Media3 versions or device decoders; this
is not general chained-Ogg support.

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
five minutes showed an active renderer without detected interruptions.

The corrected signed 0.11.6 release began a fresh eight-hour run around 15:19 UTC
on 2026-09-19, after the tester confirmed radio playback and again muted and
locked the phone. Four samples spanning three minutes showed an active renderer
with the screen asleep and charging power. Wireless debugging became unavailable
at 15:22:58 UTC, and reconnecting to the known endpoint failed. The recorder was
paused pending reconnection. This observation gap does not establish that audio
stopped, and the full eight-hour duration remains unverified. The monitor records
actual screen state and filtered renderer evidence.
The one-minute monitor validation completed with six active-renderer samples
and no detected issues. Unknown MediaSession positions were correctly treated
as unavailable telemetry.

## Screen wake and alarm controls — 2026-09-27

The POCO X3 Pro running Android 13 had standard alarm and notification access,
but Xiaomi's **Show on Lock screen** and **Open new windows while running in the
background** permissions were denied. Both were enabled through app settings.
A scheduled alarm then changed Android's reported display state from asleep to
awake and showed its controls.

A signed ARM64 development build based on 0.13.5 added a native alarm screen.
A scheduled alarm woke the display and made that screen the resumed activity;
its Dismiss and Snooze buttons were visible without opening the WebView.
An ADB-injected Volume Up press dismissed this occurrence. Volume Down also
dismissed a foreground test alarm in the main activity. Neither changed the
phone's media volume. Tapping the native Dismiss button stopped another
scheduled occurrence and closed its screen.

Device sensor diagnostics confirmed that the alarm service registered a linear
acceleration listener during scheduled ringing and removed it after dismissal.
Physical shake-to-snooze testing was deferred because the tester was unavailable.
The automated service test delivers sensor events through the registered
listener: ordinary movement and a single jolt leave ringing active, while
repeated alternating peaks create one durable snooze and remove the listener.
Test alarms do not register this gesture.

The installed development build also passed the Settings > Permissions bridge
check: exact alarms allowed, notifications on, alarm alerts high priority,
battery exemption reported, and Xiaomi/POCO-specific manual instructions shown.
The package hash read back from the phone matched the retained signed APK:
`C0561657ECF63F8195E1260009C9A5C4FE7D46D8995F313984163A2CCD56798D`.
The temporary alarm was removed, and the final exported stations, alarms and
preferences matched the original backup exactly.

Validation passed 350 frontend tests and 211 Rust tests, including the production
permission-response bridge regression. The full Android JVM suite passed 108
tests, including the motion-to-snooze service regression. Packaged startup bridge
and signing checks also passed.
This session did not qualify a credential-protected lock screen, a cold process
start, physical volume-button presses, audibility, or overnight delivery.

## Shake detection correction — 2026-09-27

A later scheduled-alarm check on the same POCO reproduced missed shake-to-snooze.
The service had registered its sensor, and a partial sensor diagnostic captured
strong movement. Replaying those samples through the original detector never
cleared its peak latch because the movement did not settle below the reset
threshold between strokes. The earlier synthetic tests had inserted quiet
samples between every reversal and had missed this failure.

The replacement follows LineageOS DeskClock's raw-accelerometer gravity filter
and short motion-strength window, preserving its six-sample accumulation and
seventh-event threshold check. A replay of the recorded linear-acceleration
data crosses the new motion threshold in every window alignment. That capture
does not contain raw accelerometer readings, so it cannot qualify the new
gravity filter or physical snooze behavior by itself.

The full Android JVM suite passed 110 tests. Synthetic regressions cover
continuous motion across axes without quiet valleys, reference sensitivity,
resting gravity, a moderate jolt, and one durable snooze followed by listener
removal. Test rings remain excluded from shake sensing.

A signed ARM64 development build based on 0.14.0 installed over the existing
phone app and opened successfully. The installed APK hash matched the retained
build:
`6B224F0390A4A4432DBF6DDE703BD00934305AB0E716626729E1430BF5B90BF5`.
Packaged DEX startup checks, signing verification, and the bundled LineageOS
attribution and Apache 2.0 licence check passed.

That build's scheduled phone check exposed a separate fallback failure. The
station connection failed with an AAC-header parsing error, then the phone's
content provider refused Media3's direct read of the default ringtone and
required Android's `Ringtone` player. The native alarm screen remained visible
with the playback error. Its two-minute automatic stop scheduled a snooze;
that pending test occurrence was cancelled before further work.

The tone correction uses `Ringtone` on Android 9 and later, including volume,
fade, audio-focus changes, liveness checks and cleanup. Fresh alarm records still
resolve their configured station or folder; a saved tone URI resumes through
the ringtone path. Android 8 keeps its existing Media3 path. All 117 Android JVM
tests passed, including the fresh-source and restored-tone cases.

The corrected signed ARM64 build installed successfully, and its on-phone APK
hash matched the retained artifact:
`41CF24A3B0CB5996ABB75A3D57C8CD38A70A8479B2CFC8AD14DB4EEB0B7DA49F`.

A controlled scheduled alarm then woke the display from dozing, showed the
native Dismiss and Snooze controls, registered the raw accelerometer at 50 Hz,
and started Android's ringtone player with media audio attributes. The earlier
system-tone error did not recur. The alarm reached its two-minute automatic
stop, which stopped the service and removed its sensor listener. No snooze was
observed, and the tester has not confirmed audible output or a physical shake
during this check, so those behaviors remain unqualified.

The temporary test alarm was removed by restoring the fresh pre-test backup.
A new export matched the entire original settings, stations and alarms data
exactly, and no Aerowave RTC alarm remained pending.

## Caprice AAC startup, fallback and fade — 2026-09-27

The same POCO X3 Pro reproduced a silent scheduled Radio Caprice Jazz Fusion
alarm using the saved AAC+ 320 kbps endpoint, `http://79.111.119.111:9009/;`.
Media3 selected an incorrect 8 kHz, six-channel AAC format, and the twelve-second
playback-progress watchdog advanced to the system tone. No backup music folder
was configured. Normal listening used the Rust relay, while a cold native alarm
opened the station directly.

A synthetic regression through Media3's real ADTS extractor reproduces that
format error when a partial frame at connection startup contains a false header.
The native extractor now aligns HTTP(S) `audio/aacp` input to four consistent
ADTS frames before reading. It also realigns a reopened connection. Local files,
other response types and the existing Opus handling keep their previous paths.

The fade previously counted connection and buffering time from alarm delivery.
It now sets the starting volume before playback and counts only advancing player
position or an active system ringtone. Buffering and focus loss pause the fade;
fallback preserves the portion already played. The automatic-stop clock remains
anchored to alarm delivery.

All 126 Android JVM tests passed. The signed ARM64 development build based on
0.14.1 passed packaged startup and signing checks, installed over the existing
app, and matched this SHA-256 when read back from the phone:
`76D0330BF483AD0EC31FB3D40D26D2FA1E2E77CBCA2FA1D1D11564BE90072FAA`.

At 16:19 local time (UTC−03), a scheduled alarm started a new app process with the
screen dozing, woke the display and showed the native alarm controls. Caprice
decoded at 44.1 kHz stereo. AudioTrack received volume 0.02 before starting at
16:19:10.535 and reached 1.0 at 16:19:30.946. Later buffering stopped progress;
after twelve seconds the alarm switched to a local MP3 at 16:19:59.402. The log
does not establish the cause of that stream interruption.

A downloaded [sample MP3](https://samplelib.com/sample-mp3.html) was placed in
`Music/Aerowave-Alarm-Backup`, selected through Android's folder picker and kept
as the configured backup. With networking disabled, another Caprice test used
that track, began at volume 0.02 and completed its fade across a track loop.
With networking disabled and that sample temporarily moved out of the folder,
the alarm reported both source failures and started Android's local ringtone
player. Networking and the sample were restored afterward.

The final exported setup matched the pre-test setup exactly except for the
requested backup folder. The original one-shot alarm was restored to 15:51 and
off; no test ring or snooze remained pending. These checks establish renderer,
volume-command and screen behavior. Audible fade perception, a credential-locked
screen and overnight reliability remain unverified.

## Do Not Disturb alarm screen on MIUI — 2026-09-28

The POCO X3 Pro on Android 13 / MIUI 14 suppressed the alarm's full-screen
notification while Do Not Disturb was active, despite high-priority alarm
notifications and the existing Xiaomi lock-screen/background-window permissions.
An initial direct activity request was also denied by Android's background
activity restrictions; ringing alone did not wake the display.

The corrected ARM64 development build based on 0.15.3 installed over the
existing app with the same signing certificate. Its installed APK matched
SHA-256 `5FB261A5B1B2FA9BE9DF5C62043EBEFADE030B5197207056EFFCE0BEF9659211`.
Android's **Display over other apps** access was enabled through system
settings. Aerowave uses that access to request its existing alarm activity;
it does not create an overlay window or change Do Not Disturb policy.

With Do Not Disturb still enabled and the app process absent before the deadline,
a scheduled local-folder alarm at 22:50 (UTC−03) started ringing at 22:50:01.226.
Android explicitly allowed the background activity because the overlay access
was granted, woke the display from asleep at 22:50:01.252, and resumed the native
alarm screen. A screenshot showed reachable Dismiss and Snooze controls.

Tapping Snooze scheduled a one-minute occurrence. With the display asleep again,
it rang at 22:51:49.045 and woke the screen at 22:51:49.066. The native screen
appeared again; tapping Dismiss cleared ringing and the pending snooze. The
temporary alarm was then removed. Readback confirmed that the original app
state and alarm definitions matched their pre-test snapshots, with no pending
alarm or ringing state. Do Not Disturb remained enabled throughout.

The updated permission row displayed **Allowed** after returning from Android
settings and was checked with real device scrolling and screenshots. Validation
also passed 392 frontend tests, eight targeted Android JVM tests, 212 Rust core
tests and two Android permission bridge tests, plus version, IPC and vendor
checks. These short screen-wake checks do not establish audible output,
credential-locked behavior, overnight delivery or behavior on other Android
versions and manufacturers.

## Qualification still open

The Android scope is not complete or release-qualified. The remaining gates
include:

- overnight playback and alarm delivery under the target phone's battery
  policy;
- a complete long-duration run of the installed signed release.
