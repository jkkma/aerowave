# Linux setup and testing

Aerowave runs as a native Tauri app on Zorin OS and Ubuntu using WebKitGTK 4.1.
The Linux configuration builds a Debian package; the default Windows NSIS
configuration remains in `tauri.conf.json`.

## Build and run

Install the [Tauri Linux prerequisites](https://v2.tauri.app/start/prerequisites/#linux)
and the audio plugins:

```bash
sudo apt update
sudo apt install build-essential curl wget file libxdo-dev libssl-dev \
  libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav
```

WebKitGTK uses GStreamer for media playback. These plugin packages supply the
parsers and decoders needed by the supported radio streams and local file
formats; the `.deb` declares them as dependencies too. See the
[WebKit multimedia documentation](https://docs.webkit.org/Ports/WebKitGTK%20and%20WPE%20WebKit/Multimedia.html)
and [GStreamer package instructions](https://gstreamer.freedesktop.org/documentation/installing/on-linux.html).

Install a current stable Rust toolchain with [rustup](https://rustup.rs/) and
[Node.js LTS](https://nodejs.org/en/download), including npm. Open a new terminal
after installation, then run from the repository root:

```bash
npm install
npm run dev
```

Quit an already running copy through its tray menu first: the single-instance
plugin otherwise raises that copy and exits the new process. Restart development
after editing `src/` so the embedded frontend is reloaded.

For an installable build:

```bash
npm run build
```

The executable is `src-tauri/target/release/aerowave`; the package is under
`src-tauri/target/release/bundle/deb/`. Install the package printed by the build
with `sudo apt install ./path/to/package.deb`, then launch Aerowave from the
applications menu or with `aerowave`. Install through apt so it resolves the
runtime dependencies. A package built on a newer distribution may not run on
older releases; [Tauri recommends building on the oldest intended base](https://v2.tauri.app/distribute/debian/#limitations).

Settings default to `~/.config/com.aerowave.radio/aerowave.json`, respecting
`XDG_CONFIG_HOME` when set. A `data` directory beside the executable takes
precedence for a portable copy. Read the active path in Settings before changing
stations or alarms during a test. Start at login creates a desktop autostart
entry for the current executable, so enable it from the installed copy.

The clock and alarm editor use the operating system's timezone rules through
Rust. WebKitGTK can carry an older timezone database than the OS; using its
JavaScript local-time conversion can make the displayed hour disagree with the
scheduler. Future alarm times are converted individually so daylight-saving
changes are respected too.

## Verification

Run the [repository checks](checks.md), including core and frontend tests, then
verify behavior in the actual Linux window. The Windows playback measurements in
the README are historical evidence for the relay design, not proof of Linux codec
or media-key support.

- Play an ordinary MP3 station for at least a minute and confirm advancing media
  time and audible output. ICY titles must not interrupt the audio.
- Test AAC/AAC+, Ogg and an HLS station. Require `canplay` or `readyState >= 3`;
  metadata alone does not establish decoding. Verify the station editor's TEST
  button against the same streams.
- Choose a local folder, play a file and let it advance to the next track. Use a
  folder available within the authorized Linux filesystem.
- Verify browse search, country and genre filters, and adding a result.
- With a safe volume and an available backup folder, test an alarm, snooze and
  dismiss. Confirm a scheduled alarm also starts while the window is hidden.
- Close to the tray, reopen through the tray menu and quit. Check desktop media
  keys separately; desktop environments and WebKitGTK versions can differ.
- Toggle Start at login, confirm its displayed state after restarting, and test
  a fresh desktop login before relying on it for an alarm.

Record the distribution, WebKitGTK version, session type, tested formats and
observed results when reporting native verification. Do not infer working audio,
alarms or media keys from a successful build alone.

## Blank window at startup

Verify the installed application with the normal user profile as well as an
isolated test profile. On 2026-09-26, the packaged app passed isolated tests but
opened a blank window with the normal cache: WebKit's content process used a
full CPU core before loading the document. A fresh `XDG_CACHE_HOME`, keeping
the original configuration and data directories, allowed startup.

A startup file trace narrowed this case to fontconfig. User-cache symlinks named
`*-le64.cache-9` pointed to incompatible `*-le64.cache-12` files. Moving those
links into a retained backup restored startup without changing settings or
stations. Do not alias different font-cache format versions. Isolate the failing
cache before repairing it, preserve a backup, and verify the installed desktop
launcher again with the normal environment and no inspector override.

## Orb performance

Check frame pacing in the native window at the desktop's actual display scale.
A browser preview or average FPS can hide a short pause every second. Compare
frame intervals with the sleep timer off and running, then hide and restore the
window. Check reduced motion and changing station artwork as well.

The sleep controls only change their DOM state when their displayed values
change, and an inactive timer has no periodic rendering work. Repeatedly writing
unchanged button attributes and hint text caused a once-per-second orb pause in
WebKitGTK on Zorin at 250% scale. Keep this check when editing the player UI.
The two large glass panels use outlines on Linux: even their inset shadow
surfaces were expensive to repaint when an active countdown changed. The glass
gradients, button shadows and orb rendering remain intact.

The orb runs at the webview's frame cadence while visible. Its spin and click
impulse use elapsed time, so a late frame does not slow the rotation. Hidden
windows and reduced motion stop its render loop; changed artwork still redraws
the stationary orb, and showing the window resets the animation's time baseline.

On 2026-09-26, Zorin 18.1 / X11 / WebKitGTK 2.52.6 was checked at 992×648 CSS
pixels and 250% display scale. In 15-second native samples, idle orb draw gaps
over 33.4 ms fell from 16 to zero, and WebKit content-process CPU fell from 19.7%
to 13.3% of one core. A fresh rebuilt app also had zero such gaps while a local
WAV played with an active sleep countdown (maximum interval 30 ms). Playback
reached readyState 4 with advancing media time; the test was muted. Hiding the
window and emulating reduced motion each stopped orb draws, artwork changes
redrew once under reduced motion, and restoring visibility/motion resumed drawing.
These are measurements on that desktop, not frame-rate guarantees for other GPUs.

## Verified on Zorin OS 18.1

The 2026-09-10 Linux port was checked on x86_64, X11 and WebKitGTK 2.52.6:

- Radio Paradise MP3 and France Inter AAC/HLS reached `readyState 4` with
  advancing playback time. Both passed the player decoder check.
- Local PCM WAV files played through the scoped file relay, advanced between
  tracks, and supported seeking and pause/resume. A request for an ungranted
  file was rejected.
- A scheduled folder alarm started from a fresh, minimized launch without a
  playback click. It looped its track, raised the alarm dialog and focused
  Dismiss. Snooze and dismissal were also checked.
- The displayed clock and Ring In used the OS time despite the browser's
  timezone data being an hour behind it.
- The interface was checked at 992×588 and 720×464 CSS pixels. Tabs and
  Settings remained reachable, and both editors could scroll to Save.

These checks establish native decoding and scheduling, not login autostart,
hardware media-key delivery, or waking a suspended computer.
