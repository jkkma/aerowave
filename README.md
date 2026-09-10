# Aerowave

An internet radio player that is also an alarm clock, in Rust + Tauri v2: glossy
Frutiger Aero glass over retro-console gunmetal, with a low-poly orb turning
slowly at the centre of it in WebGL. Click it and it hops.

Wake up to a radio station, or to a random track out of a folder you point it at.

## What it does

**Radio**

- Twelve stations seeded in (FIP and its sister channels, Radio Paradise, WFMU,
  1.FM); add, edit, tag, favourite and filter your own.
- **Ordinary stations play through a local relay, not straight off the web.** A
  media element cannot choose its own request headers, cannot see an ICY
  response, and cannot follow a playlist. Rust binds a listener on `127.0.0.1`,
  fetches the stream itself — following playlists and redirects, and speaking
  Shoutcast v1 where that is what answers — and `<audio>` plays from there.
  Loopback only, unguessable per-station tokens, `GET` only, and a `Host` that
  must be loopback too. `cargo run --example relaycheck` exercises the whole of
  it against real stations without starting the app.
- **What the relay tells a broadcaster it is turns out to matter more than
  expected, and not in the direction 0.5.0 guessed.** That release had it
  present an Edge User-Agent, on the strength of SomaFM answering `403` to one
  agent and `200` to a browser's. The measurement was an artefact — the agent
  SomaFM refused belonged to the browser the test ran in, not to anything this
  app sends — and the change quietly cost real stations, because Shoutcast v1
  sniffs the other way: hand it a browser and it serves its admin console
  instead of the stream. Every Radio Caprice mount went silent that way, and
  the player could only report the resulting HTML as something it could not
  decode. Measured over 60 stations, one per host: `Aerowave/<version>` gets
  audio from 40, the browser string from 38, and there is no station the
  browser string wins. So it says who it really is.
- A `200` carrying `text/html` is refused with that as the reason. A station
  that is down, full, or has moved answers with a page, not a stream, and
  passing one to the player produces a complaint about codecs that sends you
  looking in entirely the wrong place.
- **`MEDIA_ERR_SRC_NOT_SUPPORTED` does not mean "bad codec".** The element
  reports the same code for an HTTP error page and for a refused connection, so
  a station that looks undecodable usually is not — of a 24-station sample
  across MP3, AAC, AAC+ and Ogg, every failure that had a fixable cause was the
  server declining to answer, and none was a codec WebView2 lacked. AAC+ in
  particular plays perfectly well. A relayed station that still fails gets one
  attempt without the relay before it is written off; reconnecting on the same
  URL does not, because that part the engine really will refuse every time.
- The TEST button in the station editor does a two-part check, because the
  server answering and the player playing are still different questions: the
  Rust side proves the server answers, then a hidden media element proves the
  player can decode it. HLS uses its own hidden hls.js player for this check;
  neither a readable manifest nor metadata alone counts as success.
- A station whose format is not already known is resolved before playback so
  an extensionless endpoint or a playlist pointing to HLS reaches hls.js. That
  request also supplies the initial station headers, avoiding another immediate
  metadata request. A known HLS directory entry can start its decoder directly.
- `.pls` and `.m3u` links are followed to the real stream URL — in the relay
  now, so a playlist that does not admit to being one in its file name is
  followed just the same.
- **HLS plays**, which is about 6% of the radio-browser directory — some 3,700
  stations, France Inter and RTL among them. WebView2 will not play an `.m3u8`
  itself: it reads the playlist, reports metadata, and then sits at
  `readyState 1` for ever. So [hls.js](https://github.com/video-dev/hls.js) does
  it instead, demuxing MPEG-TS or raw AAC and feeding the element through Media
  Source Extensions. Segments are 6-in-10 MPEG-TS and 4-in-10 ADTS AAC in
  practice, which is why the demuxing is not something this app does by hand.
- **HLS fetches go through Rust too, but not through the relay.** hls.js uses
  XHR, and an XHR is not a media load: it needs CORS and an origin the page is
  allowed to reach. Reaching a loopback port would have meant opening the
  policy to `http://127.0.0.1:*` — every service on the machine that happens to
  be bound to loopback, not just ours. So HLS goes over a Tauri custom protocol
  instead: one static origin, and nothing else on the machine can knock on it.
  A session will only fetch from origins the app has itself seen — the
  redirects it followed and the playlist bodies it served — because segments
  routinely live on a different host from the playlist, so same-origin scoping
  would break a fifth of them. Every redirect and the actual DNS answers are
  checked to keep those requests off loopback and private networks. HLS connects
  directly instead of using system proxies so a proxy cannot bypass that check;
  networks that require an outbound proxy cannot use this HLS path.
- Now-playing for an HLS station is read from the ID3 tags inside the segments
  and queued against the playback clock, since a segment is parsed up to half a
  minute before it is audible. The tags are parsed here rather than by hls.js,
  whose reader decodes every text frame as UTF-8 whatever the encoding byte
  says and misreads ID3v2.3 frame sizes as synchsafe — either of which turns an
  accented title into rubbish.
- Now-playing titles are read out of the ICY metadata the server interleaves
  with the audio, **by the relay, off the connection that is playing** — so a
  title changes when the song does. The relay asks for `Icy-MetaData: 1` and
  takes the blocks back out as it copies. Asking for them and *not* stripping
  them is what a relay must never do: the blocks land every `icy-metaint`
  bytes, so a `StreamTitle='...'` ends up mid-MP3 and the decoder gives up —
  `MEDIA_ERR_DECODE`, mid-song, on a full buffer. Radio Paradise (metaint
  16000) died about sixteen seconds in, every time, while FIP, which
  interleaves nothing, played for as long as you left it. The stripper is in
  `core/`, and its test feeds the same stream in at every chunk size from one
  byte up to check the audio out is unchanged.
- A short-lived second connection is still opened once when a station starts,
  for the bitrate, genre and station name — none of which change. It repeats
  once a minute only for a station the relay is not carrying, and gives up
  after three turns that find no title.
- **ICY text has no charset**, so it is decoded as UTF-8 where that parses and
  Windows-1252 where it does not. `from_utf8_lossy` was doing both jobs and
  turning every byte it could not read into U+FFFD — a station sending the
  curly apostrophe at 0x92 showed `It?s not you`. Response headers get the
  same treatment: `to_str` only admits visible ASCII, so a station with a
  Cyrillic `icy-name` used to show no name at all.
- **Shoutcast v1 servers are read too.** They answer `ICY 200 OK` rather than an
  HTTP status line, which no HTTP client will parse — hyper throws the response
  away before a single header is seen. WebView2 plays them regardless, so those
  stations used to play perfectly while showing no bitrate, no genre and no title,
  and failing their TEST for a reason that had nothing to do with whether they
  worked. When the HTTP attempt fails, the request goes out again over a plain
  socket and the head is parsed by hand. Plaintext only: ICY predates TLS and the
  servers still speaking it are `http://` to a one.
- A dropped stream reconnects four times with a lengthening backoff; from the
  second attempt it retries through the playlist-resolved URL, unless a probe
  has already had its say about that station.
- The now-playing poll still talks to the broadcaster directly rather than to
  the relay: it wants a title, not audio, and pointing it at the relay would
  only hand it back the app's own stream.
- **The keyboard's media keys work while something is playing**, through the
  media session Chromium keeps for whatever is making sound. Next and previous
  step the same list the buttons do; play/pause holds and resumes; stop stops
  outright. The keys are not claimed globally: from standby there is no session
  for them to arrive through, so starting from cold is the play button's job.
  Claiming them globally would mean taking them off every other player on the
  machine.
- **Pause and stop are different operations, and the difference is what makes
  the play key work.** Stopping takes the source off the element, which ends
  the media session — and a session that has ended cannot be reached by the
  play key, so a pause that stopped could only ever be a second stop key.
  Pausing leaves the source where it is and the session alive, holding it in a
  paused state the next press comes back to. Resuming a file carries on from
  where it stopped; resuming a station rejoins the broadcast rather than
  playing out a buffer that is now minutes behind live. A ringing alarm will
  not pause at all: its watchdog reads the same progress timestamp a pause
  freezes, so it would answer a held ring with the backup folder, and an alarm
  that a stray keypress can silence is not an alarm.
- Because the session is Chromium's, anything that pauses the element from
  outside the app — the system taking the audio, a key nothing here claimed —
  is treated as a stop rather than ignored. Ignoring it is how the face came to
  say ON AIR, orb still turning, over silence.
- Sleep timer: 15 / 30 / 45 / 60 / 90 / 120 minutes, fading out over the last
  twenty seconds so it arrives at silence as the countdown reaches zero rather
  than starting to go quiet there. Turning it off mid-fade puts the volume back. It never touches
  a ringing alarm: an alarm cancels the timer when it fires, and a timer that
  lapses mid-ring lapses quietly.

**Browsing**

- The BROWSE tab searches [radio-browser.info](https://www.radio-browser.info/), a
  community-run directory of tens of thousands of public stations. Type a name, and
  narrow it by country or genre — both dropdowns are the directory's own lists, with
  station counts beside each entry. Press a row to listen without keeping it, `+` to
  add it to your stations. Nothing is saved until you press `+`, and nothing is
  fetched until you open the tab.
- Results are listed by country, and alphabetically inside each one, with MORE
  pages further in. The directory itself pages in name order, so the whole
  list is sorted again as each page lands: that keeps one alphabet running
  through a country rather than starting a fresh one at every page boundary.
  A station the directory has no country for sorts last. The genre list is
  the busiest two hundred tags — the directory holds tens of thousands,
  nearly all of them one station's private label.
- **All four filters narrow each other**, and none of them counts under itself —
  counting formats under the chosen format would only ever report the format
  already chosen. Choose a country and the genre list becomes the genres that
  country actually has; choose a genre and the country list becomes the
  countries that carry it; and both are counted under whatever format and
  bitrate are also set, because "Paraguay (68)" beside a 320k filter promises
  stations the next search cannot find.
- Format and bitrate are annotated rather than rebuilt. They are short fixed
  lists — five formats the player can open, nine bands of bitrate — so an
  option with nothing behind it is greyed out where it stands rather than
  removed: a list of five that reshuffles as you narrow is harder to use than
  one that keeps its shape. Pick Iceland and you get `MP3 (10)`, `AAC+ (15)`,
  `FLAC (0)` greyed. A round bitrate matches exactly: `192k (3)` is three
  stations at 192k, not three at 192k or better, so the counts do not nest and
  they do not add up to the country either — plenty of stations are encoded at
  something that is nobody's round number. `low` and `high` are the two
  exceptions, and they are ranges rather than numbers: everything under 48k and
  everything over 320k, which without a band of their own could only be reached
  by asking for any bitrate at all. They still leave gaps — 56k and 112k are in
  no band — and none of them counts the stations the directory holds no bitrate
  for, which report 0. Unreported is not the same as low, and only `Any
  bitrate` keeps those.
- A filter you have already set stays selectable even when it falls to zero,
  or the dropdown would refuse to offer what it is currently set to. The
  results simply come back empty, which is honest; silently un-setting a filter
  you chose would not be.
- Duplicate submissions are collapsed twice over: within a page as it arrives,
  and again against everything already on screen, so pressing MORE cannot bring
  back a stream you are already looking at. Matching is on the stream URL,
  normalised for case, a trailing slash and the scheme, since the same stream
  is often submitted under two names — and more often under one name with
  `http` and `https`. Of 5000 entries sampled, dropping the scheme merged 61
  pairs the whole URL kept apart and the trailing slash one more; a default
  port and a leading `www.` merged nothing, so neither is stripped. The three
  places that compare streams — the search, the tally, and the webview's own
  pass over what is already on screen — have to read a URL the same way, or a
  row one of them collapsed reappears from another.
- **Which duplicate survives is decided by votes**, not by whichever the
  directory sorted first. `radio.plaza.one/ogg` is filed both as "Nightwave
  Plaza" (171 votes) and as "Vaporwave" (379); ordered by name the first won,
  so filtering to OGG at 64k showed the station under a name its listeners do
  not use and the one they do use looked missing. The surviving row keeps the
  place the stream first appeared, so a page stays in the order it was paged
  in.
- Every count is of what the list will actually show, not of what the directory
  holds. The same station is submitted more than once all the time — Albania's
  two AAC+ stations were "Radio One - Tirana 95.2 FM" and "RadioOne", the same
  stream twice — so the tally collapses duplicate streams and drops nameless or
  unplayable entries exactly as the search does. A count that promises two and
  delivers one is the thing these counts exist to avoid.
- radio-browser publishes no crossed counts, so all of this is tallied from the
  stations themselves — a megabyte or two — which is why it happens only when a
  filter changes, and is remembered for the rest of the run. Nothing is tallied
  at all until something is narrowing. For the largest countries the tally reads
  the first five thousand stations, so those counts are a floor rather than a
  total, and say so with a `+`.
- The searching happens in Rust: it asks the directory which mirrors are up, picks
  one at random and stays on it for the session, and says which app is calling. A
  page comes back already tidied — stations the directory's own checker cannot
  reach are left out, duplicate submissions of one stream are collapsed, and
  anything that is not an `http(s)` address is dropped rather than saved as a
  station that could only ever fail.
- HLS entries are flagged in the row and play through hls.js.
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
- So does the track a folder alarm rings on: it is drawn once, when the alarm
  first goes off, and every snooze after it comes back to that same file. A
  snooze is the same alarm returning, and waking to a different song each time
  it does reads as a different alarm rather than the one you set. Dismissing it
  ends the hold, so the next day draws again — as does pointing the alarm at
  another folder, or the held file going missing between snoozes.
- Due snoozes wait their turn while another alarm rings. Turning an alarm off
  cancels its pending snooze; a test ring can be dismissed but cannot schedule
  a real snooze.
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

For development with Codex, start with [AGENTS.md](AGENTS.md). Project skills,
reviewer agents and automated checks are included; see
[checks and hook trust](docs/checks.md) for activation and standalone commands.
The [Windows testing guide](docs/windows-testing.md) preserves native input and
accessibility lessons, and the [development handoff](docs/development-handoff.md)
records the older local work that still needs reconciliation with this version.

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
