# Android preview

The first Android milestone reuses the station directory, saved stations,
settings and Rust stream resolver. A Tauri plugin runs Media3 in a foreground
media service so playback does not depend on the page staying visible.
The interface adapts to touch and portrait screens.

Android 8.0 (API 26) is the minimum configured version. The initial build target
is ARM64. Other architectures and Android versions need separate device tests.

## Build on Windows

Install Node, Rust, a JDK 21 and the Android command-line tools or Android Studio.
Set `JAVA_HOME` and `ANDROID_HOME` if they are outside the usual locations.
Install these packages with the Android SDK manager:

```powershell
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
rustup target add aarch64-linux-android
npm ci
pwsh -NoProfile -File tools/android-build.ps1
```

The helper uses `NDK_HOME` when set, otherwise NDK 28.2.13676358 under the SDK.
It also finds Android Studio's JDK or a JDK installed under
`%LOCALAPPDATA%\Android\jdk`. Its environment changes last only for the current
PowerShell process. The generated Android project is tracked under
`src-tauri/gen/android`; do not regenerate it over the local application changes.

The debug APK is written below
`src-tauri/gen/android/app/build/outputs/apk/`. Debug signing is for local
development. `-Release` builds the optimized variant; distributing a release
also requires a private Android signing key and release validation.

With a connected phone, install the ARM64 debug APK:

```powershell
adb devices -l
adb -s <device> install -r <path-to-debug-apk>
adb -s <device> shell am start -n com.aerowave.radio/.MainActivity
```

For wireless ADB, pairing uses the port in the pairing-code dialog. Connecting
uses the separate port on the main Wireless debugging page. Keep the phone and
computer on a network that permits communication between clients.

## Playback boundaries

- Ordinary streams use the existing loopback relay, including playlist
  resolution, Shoutcast handling and ICY stripping.
- HLS uses Media3's HLS decoder directly. Its HTTP client validates public DNS
  answers and redirects, does not use an outbound proxy, and identifies itself
  as `Aerowave/<version>`.
- Native audio focus, headphone disconnection and media controls belong to the
  media service. The page reads native state on return. A readable manifest or
  buffered metadata alone is not a successful playback test.
- HTTP is enabled for loopback playback and public broadcasters that do not
  offer HTTPS. The webview's existing content security policy remains in place.
- Settings live in the Android application's private config directory. Desktop
  portable-folder detection does not run on Android.

## Sleep timer

Start a station, then choose 15, 30, 45, 60, 90 or 120 minutes under Sleep timer.
The native playback service owns the deadline and fades audio over the last
20 seconds, including while the screen is locked. Choosing another duration
restarts the timer; Off cancels it and restores the selected volume. Changing
stations keeps the timer, and Stop cancels it. Pausing leaves the deadline
running; playback cannot resume itself after expiry.

The countdown uses elapsed time, so changing the phone's clock does not extend
it. Returning to the app or reloading its page reads the native countdown and
playback state. If Android terminates the playback service or process, audio
stops and the timer is cleared; it does not start playback after a reboot.
This feature stops audio only and needs no exact-alarm permission.

Media3 already [holds a wake lock while playing or buffering](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer.Builder#setWakeMode(int)).
The timer uses [elapsed realtime](https://developer.android.com/reference/android/os/SystemClock)
and checks again before resuming or reconnecting, because handler delays alone
do not count time spent in deep sleep. It does not acquire an additional wake
lock while paused.

## Remaining work

Alarms, snooze, local folders and backup tracks are unavailable.
The desktop scheduler does not run on Android, and its feature commands reject
attempts to enable those features. Android needs native scheduled delivery,
permission handling, reboot recovery and document access before those controls
can make reliable promises. There is no PC power or desktop tray support.

Before release, test audio across screen lock, returning to the application,
notification controls, audio-focus changes, headphone disconnection, network
loss and service/process recreation. Validate MP3, AAC and HLS on real devices,
and test portrait, landscape, large text and system insets. OEM battery
management and process termination require separate qualification.

## Initial device checks

The ARM64 debug preview was exercised on a POCO X3 Pro running Android 13 on
2026-09-19. Station search and saving worked, and the saved station and volume
survived an application restart. The interface fit the device's 360 x 800
portrait and 800 x 360 landscape viewports without horizontal overflow.

Radio Paradise MP3 and AAC streams and France Inter HLS decoded through Media3,
with real player-position samples and Android audio-renderer activity. The
webview audio element remained unused. Android reported the playback service
as foreground with its media notification; MP3 continued while the screen was
asleep, including a user-confirmed 30-second lock test. Android media commands
paused, resumed at the live stream, and stopped playback. After an explicit
process stop, reopening restored a paused station and Play created a new relay
connection successfully.

These are initial device checks, not qualification for every Android version,
OEM battery policy, long network outage or audio route. Bluetooth hardware,
headphone unplugging, audio-focus interruption and long idle periods still need
device coverage. Alarms remain outside this milestone.

## Sleep timer checks

The sleep timer debug build was installed on the same Android 13 device on
2026-09-19. The visible 15- and 30-minute controls set and replaced the native
deadline, and Off cancelled it without stopping playback. Starting a timer
without a native playback session returned an error. The portrait layout had
no horizontal overflow and each timer button had a 44 by 44 CSS-pixel target.

A one-minute timer was exercised with the webview explicitly frozen. Android's
audio renderer released the radio track about 164 ms after the deadline,
before any page execution resumed. The media session became idle, the media
notification was removed, and a native-state refresh cleared the interface's
timer and source. This proves expiry is independent of JavaScript execution;
the phone remained awake during that run.

A second one-minute test ran after the user physically locked the phone, while
connected to power. Android reported the screen asleep before, during and
after expiry, and the webview remained explicitly frozen. Audio-renderer
samples showed the fade reducing the track volume; the track was released
177 ms after the deadline. The media session became idle, the notification
disappeared and the service left foreground state before the page resumed.
Native state then reported the timer finished. Saved stations and settings
were unchanged, and the listening volume was restored after testing.

Cancellation during the fade, station replacement with an active timer, and
paused/deep-sleep/resume still need device checks. JVM tests cover monotonic
deadlines, the fade calculation, replacement/cancellation, and rejecting a play
request overtaken by expiry. Frontend tests cover native state restoration and
delayed command replies.
