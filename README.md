# Aerowave

An internet radio player that is also an alarm clock, in Rust + Tauri v2:
Frutiger Aero glass over a blue-green background, with a retro 3D orb turning
slowly at the centre of it in WebGL. Click it and it hops.

Wake up to a radio station, or to a random track out of a folder you point it at.

## What it does

**Radio**

- Add stations by stream link or discover them in Browse, then edit, tag,
  favourite and filter your collection.
- The Stations tab keeps your saved collection searchable by name or tag, with
  an All / Favourites switch and optional A–Z sorting. Sorting changes the view,
  leaving the saved order intact. Each row has separate listen, favourite and
  edit controls; Discover stations opens Browse. The station editor keeps its
  actions and stream-test feedback visible while the fields scroll.
- Manual station additions, edits, favourites and deletions become visible only
  after saving succeeds. A failed editor save keeps the draft for retry, and
  quitting waits for pending station writes.
- **Recent** keeps the last 20 stations that actually started playing, newest
  first, including stations tried in Browse. Unsaved discoveries can be saved
  from that list. History survives restarting the app and travels with backups.
- **Reorder** reveals up/down controls for the saved station order. Moves save
  before the list changes; a failed write leaves the previous order intact.
  Search and A–Z sorting remain separate views of the saved collection.
- The main tabs support Left/Right (including wraparound), Home and End, with
  one tab stop for the active tab.
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
- `.pls`, `.m3u` and `.asx` links are followed to the real stream URL — in the relay
  now, so a playlist that does not admit to being one in its file name is
  followed just the same. Relative entries resolve against the playlist's final
  address after redirects; ASX references can point to ordinary MP3 streams.
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
- Ogg Vorbis and Opus titles come from the container's comment packets, including
  new packets at chained song boundaries. Native FLAC comment blocks are read too.
  The relay observes these bytes without removing the headers the decoder needs.
  An explicit empty ICY or container title clears the previous song; a metadata
  heartbeat leaves it alone. Streams that provide no song information remain
  untitled rather than borrowing a title from another station.
- Stations such as M80 can put a `RadioInfo` XML document inside their ICY
  title. Its artist and song fields become the now-playing text instead of
  exposing the document. Long track titles occupy at most two lines, with the
  full text available on hover, so they cannot crowd out the playback controls.
- HLS text honors UTF-16 byte order and ID3 frame flags before decoding. Song
  information follows its Settings switch on every playback path, and desktop
  media controls receive the same current title and station image as the page.
- Station artwork is decoded before its address is remembered. A failed saved
  logo can fall back to another directory entry for the same submitted or resolved
  stream URL; a shared station name alone is not a match. Temporary lookup failures
  retry with a bounded delay. Artwork requests validate public destinations,
  DNS answers and redirects, using the same direct-connection rules as HLS.
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
  has already had its say about that station. Brief connections that drop again
  consume that budget; sustained decoded playback restores it.
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
  than starting to go quiet there. Choose **Stop audio**, **Sleep PC**, or
  **Shut down PC** before choosing the duration. The selected action is remembered
  for the next timer; choosing another duration replaces an active timer.
  Rust owns the deadline, including while the window is hidden or reloading.
  Durations use elapsed time, so correcting the Windows clock cannot shorten
  or extend either the timer or its final countdown. A power action delayed
  more than five seconds past its countdown is cancelled. Changing tracks keeps
  the remaining fade instead of bringing the new track back at full volume.
  Turning it off mid-fade puts the volume back. Sleep and shutdown stop the audio,
  then show a 30-second countdown that can be cancelled with its button or Escape.
  Shutdown lets other applications with unsaved work block it. A ringing alarm
  cancels the timer, and an alarm due within 45 seconds takes priority over power
  actions. An overdue power action is cancelled after a long suspend, so waking
  the PC does not immediately put it back to sleep or shut it down.
  The Windows sleep call runs separately from the alarm clock, so an automatic
  wake can prepare and ring the alarm even while that call is still returning.
  On Modern Standby PCs, Sleep PC requests display power-off to begin Windows'
  standby transition; the traditional suspend API can return "not supported"
  on machines that only provide S0 low-power idle. Other applications or drivers
  can delay low-power entry after the screen turns off. Traditional S1–S3 PCs
  continue to use the suspend API. The display request goes only to Aerowave's
  own window with a bounded wait, so an unresponsive unrelated window cannot
  delay the request or turn the display off again after the alarm.

**Browsing**

- The BROWSE tab searches [radio-browser.info](https://www.radio-browser.info/), a
  community-run directory of tens of thousands of public stations. Type a name, and
  narrow it by country or genre — both dropdowns are the directory's own lists, with
  station counts beside each entry. Press Listen or a row to try a station, and
  Save to add it to your stations. Nothing is saved until you press Save, and nothing is
  fetched until you open the tab.
- Country and genre stay beside the search; the Audio quality section opens
  format and bitrate choices when needed. Applied filters can be removed one at
  a time, and Clear all starts over. The results count says how many stations
  are loaded, not how many exist in the directory. A failed search offers Try
  again without applying unfinished edits in the search field.
- Adding a station shows a pending state until its settings file is written.
  If the write fails, Save becomes available to retry. Failed additions cannot
  be swept into a later settings save.
- Each page is listed by country, and alphabetically inside each one. MORE
  appends the next page without moving the rows already loaded, so stations
  without a country do not keep reappearing at the bottom as though they were
  new. The directory itself pages in name order. A station the directory has no
  country for sorts last within its page. The genre list is
  the busiest two hundred tags — the directory holds tens of thousands,
  nearly all of them one station's private label.
- MORE keeps the submitted search even if you edit the name field before
  searching again. Failed pages can be retried, and pages containing only
  duplicates do not hide later results. Paging stops at the directory offset
  cap instead of repeating the last page.
- **All four filters narrow each other**, and none of them counts under itself —
  counting formats under the chosen format would only ever report the format
  already chosen. Choose a country and the genre list becomes the genres that
  country actually has; choose a genre and the country list becomes the
  countries that carry it; and both are counted under whatever format and
  bitrate are also set, because "Paraguay (68)" beside a 320k filter promises
  stations the next search cannot find.
  Counts include the submitted station name too. Editing the search field takes
  effect when a new search starts, and outdated counts are cleared while their
  replacements load. A selected filter with no matches stays visible with zero.
  A selected genre matches that individual tag exactly, so `rock` does not
  also count entries tagged only `hard rock`.
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
  A zero in an incomplete sample stays selectable too: the rest of the
  directory may still contain that format or bitrate. If a tally fails, the
  filters fall back to uncounted choices instead of showing another selection's
  old counts. Missing initial country and genre lists are retried on the next
  visit or search.
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
  A higher-voted submission on a later page replaces the earlier representative
  in place.
- Every count is of what the list will actually show, not of what the directory
  holds. The same station is submitted more than once all the time — Albania's
  two AAC+ stations were "Radio One - Tirana 95.2 FM" and "RadioOne", the same
  stream twice — so the tally collapses duplicate streams and drops nameless or
  unplayable entries exactly as the search does. A count that promises two and
  delivers one is the thing these counts exist to avoid.
  Each country, genre, format and bitrate bucket counts a stream once. When
  duplicate submissions have different metadata, both classifications remain
  available, because either corresponding filtered search can find the stream.
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
  A connection failure, server error or malformed response gets one automatic
  retry within the same time limit. The retry prefers another discovered mirror;
  when only one is available, it tries that server again after a short pause.
- HLS entries are flagged in the row and play through hls.js.
- An added station is an ordinary station — editable, taggable, and usable as an
  alarm source like any other.

**Folders**

- Point it at a folder and it plays a random file from it, walking up to 8 levels
  deep. When a track ends it rolls straight on to another one.
- Recent picks are remembered so a small folder does not repeat itself.
- On desktop, the orb shows the current track's embedded album art and updates
  when you skip or go back. Tracks without usable art show the crystal again.
- Only extensions WebView2 can actually decode are listed (`mp3 m4a aac mp4 flac
  ogg opus wav webm`) — WMA is deliberately absent, because an alarm that stays
  silent is worse than one that never existed.

**Alarms**

- The next-alarm card brings the scheduled time, source, backup music and
  platform wake or permission status together. Check source and Check backup
  run only when requested, without replacing the current listening session.
  Desktop checks use a muted decoder; a folder check samples one track without
  consuming shuffle history. Android checks report server or folder availability,
  not native decoding. Results expire after five minutes and are discarded when
  their source or occurrence changes. These checks cover current conditions;
  they do not establish speaker volume, future network access or unattended wake.
  Use Test in the alarm editor to hear an alarm through the normal playback path.
- Any number of them, each with its own time, repeat days, source, volume,
  fade-in, snooze length, give-up timeout and auto-snooze.
- **Skip next** on a repeating alarm skips one scheduled date without turning
  off the routine. The card shows the skipped date and the following ring;
  **Undo skip** restores it before its scheduled time. Skips survive a restart,
  and snoozes keep their own timer. Changing the time or repeat days, or turning
  the alarm off, clears its skip.
- **Duplicate** opens an editable copy of an alarm. Its time, repeat days,
  source, volume and snooze settings are carried over; skipped dates are not.
  The copy is saved only when you choose Save alarm, and the original stays
  unchanged. A copy of an alarm that is off also starts off.
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
  A rejected automatic snooze restores the alarm instead of leaving it faded
  to silence, and consumes its snooze allowance only after acceptance. Automatic
  actions retry a limited number of times; a persistent failure leaves the
  alarm available for manual dismissal or snooze.
  The native scheduler owns the automatic-snooze tally, so reloading the page
  does not grant extra rounds. A manual Dismiss or Snooze that arrives while
  an automatic completion is pending takes precedence for that occurrence;
  a delayed request cannot change a newer occurrence of the same alarm.
- So does the track a folder alarm rings on: it is drawn once, when the alarm
  first goes off, and every snooze after it comes back to that same file. A
  snooze is the same alarm returning, and waking to a different song each time
  it does reads as a different alarm rather than the one you set. Dismissing it
  ends the hold, so the next day draws again — as does pointing the alarm at
  another folder, or the held file going missing between snoozes.
- After giving up, the previous radio station or local music resumes at its
  previous volume, including between automatic snoozes. Local music continues
  from the interrupted position with its shuffle history intact; previously
  paused music stays paused. With nothing playing before the alarm, it returns
  to silence. Manually dismissing or snoozing a ring still stops playback.
- Due snoozes wait their turn while another alarm rings. Turning an alarm off
  cancels its pending snooze; a test ring can be dismissed but cannot schedule
  a real snooze.
- No repeat days set means "once, at the next occurrence", and the alarm disables
  itself afterwards.
- Fade-in ramps the volume over up to 90 s.
- **Everything falls back to the backup folder.** Set one in Settings and it stands in
  whenever an alarm's own source will not make a sound: a station that 404s, a
  stream whose playback clock has not advanced within twelve seconds, a station
  you deleted, a folder that has moved, a file that will not decode. If even the backup folder
  is unusable the alarm still fires — the window comes up in red and says why.
  There is no synthesised fallback tone.
  Folder searches run outside the alarm clock, so a slow drive or disconnected
  share cannot stop the scheduler. The preferred folder and backup are searched
  concurrently: a ready backup can take over after two seconds, and source
  selection stops waiting after five seconds. A result arriving after dismissal
  or replacement cannot restart the alarm. Filesystem work uses a bounded worker
  pool; exhausted capacity is reported as unavailable rather than creating more
  blocked threads.
  Each occurrence keeps the alarm settings it claimed. Edits apply to future
  occurrences; disabling or deleting an alarm still cancels an unresolved source.
- The same rule applies to ordinary listening: when a station gives up after its
  four reconnects, the backup folder takes over.
- Missed alarms are caught up: if the machine was asleep through the alarm minute,
  it rings on wake as long as it is no more than 15 minutes late. Short sleeps
  that skip the whole alarm minute are caught up too.
- **Wake PC for alarms** in Settings is enabled by default on Windows. Aerowave
  requests a wake timer 45 seconds before the next alarm or snooze, giving the
  network and audio device time to resume, and requests both the system and
  display stay awake through the ring. A timer wake otherwise leaves the display
  off; showing the alarm window alone does not request display power. The active
  operation includes the alarm's snooze cycle: the system stays awake between
  rings while a snooze is pending, but the display can turn off until preparation
  for the next ring. Final dismissal or automatic stopping without another
  snooze releases the requests, unless another alarm is preparing, ringing or
  snoozed. Cancelling the last pending snooze also ends its hold. Snoozes still
  request wake timers in case the user explicitly puts the PC to sleep. An
  explicit Sleep PC timer releases the hold when it dispatches; an idle request
  must not undo that action. A running sleep timer keeps the system awake until
  its deadline. Scoping the hold to active alarm work follows Microsoft's
  [guidance to release execution-state requests after the active operation](https://learn.microsoft.com/en-us/windows/win32/power/system-sleep-criteria).
  Editing, disabling or deleting an alarm updates the next wake timer.
  Keep Aerowave running, including in the tray: quitting cancels its timers.
  Alarm and wake-setting changes become active only after their configuration
  has been written successfully. A failed save keeps the previous live state.
  Wake support depends on the PC and its current AC/battery power plan. Settings
  reports the current policy; if blocked, enable **Sleep > Allow wake timers**
  in Windows' advanced power settings. Aerowave does not change that system setting.
  Modern Standby may suspend desktop applications, so automatic waking there is
  unverified and not guaranteed. Settings keeps a visible warning on those PCs
  even when Windows accepts a timer; it reports a registered request rather than
  confirmed wake support. The Sleep PC countdown also warns when automatic wake
  is off or unconfirmed. Keep the PC awake for dependable alarms on those systems.
  Alarms cannot turn on a shut-down PC. The native adapter uses
  [Windows resume-capable waitable timers](https://learn.microsoft.com/en-us/windows/win32/api/synchapi/nf-synchapi-setwaitabletimer);
  Microsoft's [Modern Standby guidance](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/integrating-apps-with-modern-standby)
  explains the desktop-app limitation.
  PC sleep, shutdown and wake actions are available on Windows; Linux retains
  the audio sleep timer and reports that PC power controls are unavailable.
- Ringing raises the window over whatever else is on screen.

## Installing it

### Android

The Android port starts with station browsing, saved stations and native radio
playback. Android's Media3 service owns playback and media controls; ordinary
streams still use the Rust relay, while HLS goes directly through Media3 with
public-address validation. The desktop playback paths above remain unchanged.

The sleep timer, alarms, snooze and music-folder playback run in native Android
services. AlarmManager restores scheduled delivery after a reboot; the system
folder picker grants persistent read access to chosen music. Cold-start alarms
connect through their own guarded native player and fall back to backup music,
then Android's alarm sound when necessary. Desktop alarm behavior above is
unchanged. See the [Android build and testing guide](docs/android.md) for device
evidence and limitations, and [Android releases](docs/android-release.md) for
protected signing keys and optimized APKs.

If Android settings cannot be read, Settings shows the storage error and startup
keeps the native alarm store's existing source information. Recover the settings
or restore a backup before changing setup.

### Zorin OS / Ubuntu Linux

Download the x64 `.deb` from [Releases](https://github.com/jkkma/aerowave/releases),
then install it with `sudo apt install ./Aerowave_<version>_amd64.deb` from the
download directory. Open Aerowave from the applications menu. The package declares
the WebKitGTK and GStreamer dependencies needed for the interface and audio
playback. The 0.10.0 package was built and tested on Zorin OS 18.1 / Ubuntu 24.04.

To build from source, follow the [Linux setup and testing guide](docs/linux-testing.md).

Settings normally live in `~/.config/com.aerowave.radio/aerowave.json`, or under
`$XDG_CONFIG_HOME/com.aerowave.radio/` when that variable is set. Settings shows the
active settings location.

### Windows

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

Or portable: take the Windows zip from [Releases](https://github.com/jkkma/aerowave/releases),
unzip it anywhere and run `aerowave.exe`. Keep the `data` folder next to the exe and
the copy stays portable — settings live in `data\aerowave.json` and the webview's
cache in `data\webview\`, and nothing is written to your user profile. Delete `data`
and it falls back to `%APPDATA%\com.aerowave.radio`. Settings tells you which of the two
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

On Linux, follow the [native Linux build instructions](docs/linux-testing.md).
Tauri automatically merges `src-tauri/tauri.linux.conf.json` to build a `.deb`;
Windows builds continue to produce the NSIS installer.

On Windows, this needs Rust, Node, the MSVC or MinGW build tools and WebView2.

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

They live in the `aerowave-core` crate. On Windows the app crate links WebView2 and
the Win32 GUI stack, and a test binary built from it will not load outside a real
app process, so its harness is switched off in `Cargo.toml` and anything worth
testing lives in `core/` instead. Linux uses WebKitGTK and GStreamer; a successful
core test run does not establish native playback on either platform.

## How it is put together

For development with Codex, start with [AGENTS.md](AGENTS.md). Project skills
and reviewer agents are included. Manual repository checks live in
`tools/checks/`; AGENTS.md lists the commands.
The [Linux testing guide](docs/linux-testing.md) covers native builds and playback
checks. The [Windows testing guide](docs/windows-testing.md) preserves native input
and accessibility lessons, and the [development handoff](docs/development-handoff.md)
records the older local work that still needs reconciliation with this version.

```
src/                 index.html, styles.css, app.js  — the face and playback
  orb3d.js           the retro, smoothly shaded WebGL orb
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

Config lives in `data/aerowave.json` beside the executable when that folder exists.
Otherwise it uses `%APPDATA%\com.aerowave.radio\aerowave.json` on Windows and
`$XDG_CONFIG_HOME/com.aerowave.radio/aerowave.json` on Linux, with `~/.config` as the
Linux default when `XDG_CONFIG_HOME` is unset.

`python tools/package.py --build` produces the portable zip the Scoop manifest points
at, and prints its SHA-256.

## Notes and limits

- **Backup & restore** in Settings exports a versioned JSON file containing
  stations, alarms, preferences, and recent stations. Restore validates the
  file and previews its counts before replacing the setup. Imported alarms
  start off and skipped occurrences are cleared; review folders and alarms
  before enabling them. Startup, PC wake, and sleep-action choices stay local.
  A recovery copy is kept before replacement and can be previewed with
  **Previous setup**. Export uses a new file so older backups are preserved.
  Android uses its system document picker; folder access does not transfer
  between devices. Its alarm store and settings file are separate: if the
  final settings commit fails after native restore, the error gives the
  recovery location and asks for a retry, while imported alarms remain off.
- Closing the window hides it to the tray by default, so alarms keep working.
  Turn that off in Settings, or use Quit Aerowave to really exit. Alarms only ring
  while Aerowave is running. Close and Quit finish pending settings saves first,
  including when requested from the native window or tray. If a save fails,
  the app stays open and shows the error instead of silently losing the change.
- "Start at login" is off by default. It creates a Windows registry entry or a
  Linux desktop autostart entry outside a portable copy's own folder, and removes
  that entry when turned off.
- The window is undecorated with its own titlebar, to get the glass look. It is
  draggable by the titlebar and resizable from the edges.
- The orb uses smooth shading over a subdivided icosahedron, so lighting gives
  it depth without visible triangular faces. Its 64-pixel aqua texture uses
  broad, randomly warped patches with darker blue shades and ordered dithering.
  The pattern changes on each launch and stays stable throughout that run,
  without a white streak across its surface. It spins on one vertical axis, a
  little faster while something is playing, and a click gives it a
  half-second spin-up and a short, stepped hop. Station artwork fits inside a
  pale aqua-edged inset without cropping, with a faint rim light and no glare
  over the logo. Reduced motion stops the spin, hop and ripple.
  If WebGL will not start it stays hidden and the painted CSS orb underneath
  carries on as before.
- It renders into a 112x112 buffer that CSS scales up with `image-rendering:
  pixelated`, and antialiasing is off. Three-point texture filtering and a
  limited palette keep the N64-inspired look.
- The orb is decorative. It is not an audio analyser — routing a cross-origin
  stream through Web Audio taints it and Chromium outputs silence, so its motion
  is time-based on purpose.
- Each picked local file is granted individually. Windows plays it through
  Tauri's asset protocol; Linux serves the granted file through the loopback
  listener, with byte ranges for seeking, because WebKitGTK cannot reliably
  play audio from a custom scheme. File URLs use opaque tokens, and the
  listener never accepts filesystem paths from HTTP requests.
- The interface uses native system fonts with local monospace fallbacks for
  the clock and readouts; the webview loads no webfonts.

## Licence

MIT — see [LICENSE](LICENSE).

Three.js is bundled in `src/vendor/` under its own MIT licence, kept alongside
it in `src/vendor/THREE-LICENSE.txt`.

The seeded stations are other people's broadcasts: the URLs are here, the audio
is theirs, and each broadcaster sets its own terms for listening and
redistribution.
