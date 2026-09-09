# Aerowave

Internet radio player and alarm clock. Rust + Tauri v2 behind a plain
HTML/CSS/JS front end — no bundler, no framework, the Tauri API reached
through `window.__TAURI__`.

`README.md` carries the domain reasoning: why ordinary stations play through a
local relay, why the User-Agent it presents matters, how the browse filters
count. Read it before changing playback, browsing or alarm behaviour — it
records measurements that are expensive to redo.

## Commands

```bash
npm run dev      # tauri dev
npm run build    # NSIS installer under src-tauri/target/release/bundle
```

```bash
cd src-tauri && cargo test -p aerowave-core     # 45 tests, ~1s — the loop to use
cd src-tauri && cargo test --workspace          # same tests, but builds the app crate too
cd src-tauri && cargo run --example relaycheck  # real relay, real stations, no app
```

`python tools/package.py --build` builds the portable zip and prints its SHA-256.

`/release` cuts a release end to end, including the Scoop manifest in the
separate `jkkma/scoop-ayylmao` repo. `/station-triage` works through why a
station will not play, starting from `relaycheck` rather than from the code.

## Layout

| Path | What |
|---|---|
| `src/app.js` | Playback, face, all IPC. Banner comments (`// ---- player ---`) divide it. |
| `src/orb3d.js` | The WebGL orb. `tools/orb-preview.html` renders it without a rebuild. |
| `src-tauri/src/lib.rs` | Commands, tray, window lifecycle, the `invoke_handler` list |
| `src-tauri/src/scheduler.rs` | The clock: ticks every second, emits `alarm-fire` |
| `src-tauri/src/stream.rs` | Playlist resolution, ICY metadata, Shoutcast v1 fallback |
| `src-tauri/src/relay.rs` | The loopback relay ordinary stations play through |
| `src-tauri/src/hls.rs` | HLS over a Tauri custom protocol — deliberately not the relay |
| `src-tauri/core/` | Pure logic, no GUI deps — **the only place tests can run** |
| `tools/hooks/` | The four hook scripts below. Plain Python, run by the harness, not by the app. |
| `.claude/` | Two reviewer agents, the two skills above, and the example hook wiring |

## Gotchas

**Tests only run in `core/`.** The app crate links WebView2 and the Win32 GUI
stack, so a test binary built from it will not load outside a real app process;
that is what `test = false` in `Cargo.toml` is for. Anything worth testing goes
in `core/`.

**`tauri dev` watches `src-tauri/` only.** Edits under `src/` are picked up at
the next launch, not on save. `touch src-tauri/src/main.rs` forces a rebuild
that reloads both.

**An installed copy blocks `npm run dev`.** Both share the identifier
`com.aerowave.radio`, so `tauri-plugin-single-instance` makes the dev build
surface the installed one and exit 0 with no window. Quit the installed copy
first rather than killing it — it may be holding a snooze.

**A dev build and an installed copy do not share settings.** A copy with a
`data` folder beside the exe is portable and uses it; anything else uses
`%APPDATA%\com.aerowave.radio\aerowave.json`. Stations added while testing land
in whichever one was running.

**Judge playback by `readyState >= 3` or `canplay`, never `loadedmetadata`.**
HLS fires `loadedmetadata` and then stalls at readyState 1 for ever, so
`loadedmetadata` reports a dead stream as working.

**Do not present a browser User-Agent, and do not put HLS through the relay.**
Both were measured, both broke real stations; the README says what the
measurements were.

**The version lives in five files** — `package.json`, `package-lock.json` (two
fields), `src-tauri/Cargo.toml`, `src-tauri/core/Cargo.toml`,
`src-tauri/tauri.conf.json` — plus two in `Cargo.lock` that only a build
rewrites, so build between the bump and the commit.

**Two hooks can refuse to end a turn.** `tools/hooks/check_version.py` blocks
while those five files disagree, and `check_seam.py` blocks when `app.js`
invokes a command that is not in the `invoke_handler` list. Both stay silent
when things are clean, and both exist because the failure they catch produces
no compile error and no failing test — it surfaces as a button that does
nothing, or an installer whose version contradicts its own Scoop manifest.
Editing anything under `core/` also runs `cargo test -p aerowave-core`, and
edits to `src/vendor/` are refused outright: those are upstream builds to be
replaced wholesale, licence and all.

The scripts are tracked but the wiring is not. Copy
`.claude/settings.example.json` to `.claude/settings.local.json` to switch them
on, and restart before expecting them to fire. Keeping the live file untracked
is deliberate: a committed `settings.json` runs these scripts on anyone who
clones the repo and opens Claude Code, which is a lot to hand a stranger for
the sake of saving one copy.

## Conventions

- A new command must be registered in the `invoke_handler` list in `lib.rs`;
  the front end reaches it as `invoke("name")`. The events crossing the seam
  are `alarm-fire`, `alarms-updated`, `settings-updated`, `tray-stop`,
  `icy-title`.
  `check_seam.py` will not let a turn end with one side of that missing.
- The CSP in `tauri.conf.json` allows scripts from the app's own origin only.
  Libraries are vendored into `src/vendor/` with their licence beside them,
  never fetched from a CDN. New window permissions go in
  `src-tauri/capabilities/default.json`.
- Comments explain why, in prose, and earn their space when the reasoning is
  not visible in the code. Match that rather than annotating what a line does.
- Commits go straight to `main`: sentence-case title, then a prose body giving
  the reasoning rather than a bulleted list of changes.
