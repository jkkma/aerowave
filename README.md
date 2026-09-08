# Aerowave

An internet radio player that is also an alarm clock, in Rust + Tauri v2: glossy
Frutiger Aero glass over retro-console gunmetal, with a low-poly orb turning
slowly at the centre of it in WebGL. Click it and it hops.

Wake up to a radio station, or to a random track out of a folder you point it at.

## What it does

**Radio**

- Twelve stations seeded in (FIP and its sister channels, Radio Paradise, WFMU,
  1.FM); add, edit, tag, favourite and filter your own.
- **A station that answers HTTP is not the same as a station that plays.** Several
  well-known broadcasters (SomaFM's mounts among them) return a perfectly good
  `audio/mpeg` response that a Chromium media element then refuses with
  `MEDIA_ERR_SRC_NOT_SUPPORTED`. Every seeded station was checked by loading it
  in a real browser engine, not just by fetching it, and the TEST button in the
  station editor does the same two-part check: the Rust side proves the server
  answers, then a hidden media element proves the player can decode it.
- A source the player cannot decode fails immediately rather than burning four
  reconnects on something that will never work.
- `.pls` and `.m3u` links are followed to the real stream URL. HLS is detected and
  called out rather than silently playing nothing — WebView2 has no HLS decoder.
- Now-playing titles are read out of the ICY metadata the server interleaves with
  the audio, polled every 25 s. The `<audio>` element cannot see that metadata, so
  the Rust side opens a second short-lived connection to read it.
- **Shoutcast v1 servers are read too.** They answer `ICY 200 OK` rather than an
  HTTP status line, which no HTTP client will parse — hyper throws the response
  away before a single header is seen. WebView2 plays them regardless, so those
  stations used to play perfectly while showing no bitrate, no genre and no title,
  and failing their TEST for a reason that had nothing to do with whether they
  worked. When the HTTP attempt fails, the request goes out again over a plain
  socket and the head is parsed by hand. Plaintext only: ICY predates TLS and the
  servers still speaking it are `http://` to a one.
- A dropped stream reconnects four times with a lengthening backoff; from the
  second attempt it retries through the playlist-resolved URL.
- Sleep timer: 15 / 30 / 60 / 90 minutes, fading out over the last twenty seconds
  so it arrives at silence as the countdown reaches zero rather than starting to
  go quiet there. Turning it off mid-fade puts the volume back. It never touches
  a ringing alarm: an alarm cancels the timer when it fires, and a timer that
  lapses mid-ring lapses quietly.

**Browsing**

- The BROWSE tab searches [radio-browser.info](https://www.radio-browser.info/), a
  community-run directory of tens of thousands of public stations. Type a name, and
  narrow it by country or genre — both dropdowns are the directory's own lists, with
  station counts beside each entry. Press a row to listen without keeping it, `+` to
  add it to your stations. Nothing is saved until you press `+`, and nothing is
  fetched until you open the tab.
- Results come back in name order, A to Z, and MORE pages further in. The genre
  list is the busiest two hundred tags — the directory holds tens of thousands,
  nearly all of them one station's private label.
- The two filters narrow each other: choose a country and the genre list becomes
  the genres that country actually has, counted within it; choose a genre and the
  country list becomes the countries that carry it. radio-browser publishes no
  crossed counts, so this is tallied from the stations themselves — a megabyte or
  two — which is why it happens only when a filter changes, and is remembered for
  the rest of the run. For the largest countries the tally reads the first five
  thousand stations, so those counts are a floor rather than a total.
- The searching happens in Rust: it asks the directory which mirrors are up, picks
  one at random and stays on it for the session, and says which app is calling. A
  page comes back already tidied — stations the directory's own checker cannot
  reach are left out, duplicate submissions of one stream are collapsed, and
  anything that is not an `http(s)` address is dropped rather than saved as a
  station that could only ever fail.
- HLS entries are flagged in the row rather than hidden: the station may well be
  worth keeping, but WebView2 has no decoder for it.
- An added station is an ordinary station — editable, taggable, and usable as an
  alarm source like any other.

**Folders**

- Point it at a folder and it plays a random file from it, walking up to 8 levels
  deep. When a track ends it rolls straight on to another one.
- Recent picks are remembered so a small folder does not repeat itself.
- Only extensions WebView2 can actually decode are listed (`mp3 m4a aac mp4 flac
  ogg opus wav webm`) — WMA is deliberately absent, because an alarm that stays
  silent is worse than one that never existed.

**Alarms**

- Any number of them, each with its own time, repeat days, source, volume,
  fade-in, snooze length, give-up timeout and auto-snooze.
- A ringing alarm keeps going until you dismiss it, or until its give-up
  timeout: anything from a minute to two hours, or `never` to make dismissing
  it the only way to stop it. It does not fall quiet between tracks either -
  when a local file ends the next random one starts. A value written straight
  into `aerowave.json` that the dropdown does not offer is kept rather than
  reset, so any number of minutes works.
- Giving up fades out over six seconds instead of cutting the sound dead, and can
  hand the alarm to a snooze rather than ending it: auto-snooze off, once, twice,
  or as many rounds as you set. The tally belongs to the ring — dismissing it, or
  the next day's alarm, starts the budget over. A test ring fades out too but
  never schedules a real snooze.
- No repeat days set means "once, at the next occurrence", and the alarm disables
  itself afterwards.
- Fade-in ramps the volume over up to 90 s.
- **Everything falls back to the backup folder.** Set one in SETUP and it stands in
  whenever an alarm's own source will not make a sound: a station that 404s, a
  stream that has not started within twelve seconds, a station you deleted, a
  folder that has moved, a file that will not decode. If even the backup folder
  is unusable the alarm still fires — the window comes up in red and says why.
  There is no synthesised fallback tone.
- The same rule applies to ordinary listening: when a station gives up after its
  four reconnects, the backup folder takes over.
- Missed alarms are caught up: if the machine was asleep through the alarm minute,
  it rings on wake as long as it is less than 15 minutes late.
- Ringing raises the window over whatever else is on screen.

## Installing it

With [Scoop](https://scoop.sh):

```
scoop bucket add ayylmao https://github.com/jkkma/scoop-ayylmao
scoop install aerowave
```

`ayylmao` is only what the bucket is called on your machine — Scoop takes whatever
name you type there, and the bucket carries other apps besides this one.

If you added that bucket before it moved out of `jkkma/nmkoder`, `scoop bucket rm
ayylmao` first: adding the same bucket twice under two names is what produces
Scoop's `WARN Multiple buckets contain manifest ...` line.

Or portable: take the zip from [Releases](https://github.com/jkkma/aerowave/releases),
unzip it anywhere and run `aerowave.exe`. Keep the `data` folder next to the exe and
the copy stays portable — settings live in `data\aerowave.json` and the webview's
cache in `data\webview\`, and nothing is written to your user profile. Delete `data`
and it falls back to `%APPDATA%\com.aerowave.radio`. SETUP tells you which of the two
a running copy is using.

Either way it needs the WebView2 runtime, which ships with Windows 11 and current
Windows 10. `WebView2Loader.dll` sits beside the exe in the zip and has to stay there.

`aerowave.exe` is not code-signed, so the first time a downloaded copy runs Windows
SmartScreen says "Windows protected your PC" and names an unknown publisher; **More
info** then **Run anyway** is the way past it. Scoop checks the download against the
hash in the manifest for you. To check a manual download yourself, compare

```
Get-FileHash .\Aerowave-<version>-win-x64.zip -Algorithm SHA256
```

against the `hash` field in the [bucket manifest](https://github.com/jkkma/scoop-ayylmao/blob/main/bucket/aerowave.json)
or the checksum quoted in the release notes.

## Building it

Needs Rust, Node, the MSVC or MinGW build tools and WebView2.

```
npm install
npm run dev          # tauri dev
npm run build        # NSIS installer in src-tauri/target/release/bundle
```

There is no bundler and no frontend framework: `src/` is plain HTML, CSS and JS
served straight from disk, and the Tauri API is reached through `window.__TAURI__`.
Three.js is vendored into `src/vendor/` rather than fetched from a CDN, because the
app has to work offline and its content security policy only allows scripts from
its own origin.

Tests (ICY and playlist parsing, weekday matching, catch-up rules, directory
tidying):

```
cd src-tauri && cargo test --workspace
```

They live in the `aerowave-core` crate. The app crate links WebView2 and the Win32
GUI stack, and a test binary built from it will not load outside a real app
process, so its harness is switched off in `Cargo.toml` and anything worth testing
lives in `core/` instead.

## How it is put together

```
src/                 index.html, styles.css, app.js  — the face and playback
  orb3d.js           the low-poly WebGL orb
  vendor/            three.js, vendored
src-tauri/src/
  lib.rs             commands, tray, window lifecycle
  scheduler.rs       the clock: ticks every second, decides when to ring
  store.rs           stations/alarms/settings, one JSON file, atomic writes
  library.rs         folder scanning and the random pick
  stream.rs          playlist resolution, ICY metadata, the Shoutcast fallback
  browse.rs          searching the radio-browser.info directory
src-tauri/core/      pure logic, no GUI dependencies, where the tests are
tools/make_icon.py   draws the app icon (Pillow)
tools/shot.ps1       screenshots the running window, for checking the look
tools/orb-preview.html  the orb on its own, for working on it without a rebuild
                        (serve the repo root, then open /tools/orb-preview.html)
tools/package.py     builds the portable zip and prints its hash
tools/drive.ps1      clicks and types at the running window, for end-to-end tests
```

**The clock lives in Rust, deliberately.** WebView2 throttles timers in hidden and
occluded windows, so an alarm scheduled with `setInterval` would only be reliable
while you were already looking at it. The Rust thread ticks once a second and
emits `alarm-fire`; the webview is told when to ring and what to play, and only
does the playing. Snooze goes back through Rust for the same reason.

Config lives in `data\aerowave.json` beside the exe when that folder exists, and in
`%APPDATA%\com.aerowave.radio\aerowave.json` otherwise.

`python tools/package.py --build` produces the portable zip the Scoop manifest points
at, and prints its SHA-256.

## Notes and limits

- Closing the window hides it to the tray by default, so alarms keep working.
  Turn that off in SETUP, or use QUIT AEROWAVE to really exit. Alarms only ring
  while Aerowave is running.
- The one thing a portable copy writes outside its own folder is the
  "Start with Windows" registry entry, which is off by default and is removed
  again when you turn it off.
- The window is undecorated with its own titlebar, to get the glass look. It is
  draggable by the titlebar and resizable from the edges.
- The orb is a flat-shaded icosahedron with a 64-pixel texture stretched over
  its triangles. The facets are what make the rotation readable — a smooth
  sphere cannot show that it is turning. It spins on one vertical axis, a
  little faster while something is playing, and a click gives it a
  half-second spin-up and a hop. If WebGL will not start it stays hidden and
  the painted CSS orb underneath carries on as before.
- It renders into a 112x112 buffer that CSS scales up with `image-rendering:
  pixelated`, and antialiasing is off: chunky pixels and stairstepped edges, a
  PS2 running at native resolution on a screen far too big for it.
- The orb is decorative. It is not an audio analyser — routing a cross-origin
  stream through Web Audio taints it and Chromium outputs silence, so its motion
  is time-based on purpose.
- Local files reach the webview through Tauri's asset protocol, whose scope starts
  empty; each picked file is opened to it individually at the moment it is chosen.
- Everything renders in stock Windows fonts (Bahnschrift, Cascadia Mono, Segoe
  UI) — the webview loads no webfonts.

## Licence

MIT — see [LICENSE](LICENSE).

Three.js is bundled in `src/vendor/` under its own MIT licence, kept alongside
it in `src/vendor/THREE-LICENSE.txt`.

The seeded stations are other people's broadcasts: the URLs are here, the audio
is theirs, and each broadcaster sets its own terms for listening and
redistribution.
