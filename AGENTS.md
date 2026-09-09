# Aerowave

Codex project instructions. Reviewer agents live in `.codex/agents/`, reusable
workflows in `.agents/skills/`, and native hook wiring in `.codex/hooks.json`.
Read `docs/checks.md` for hook trust and manual checks, `docs/windows-testing.md`
before native input tests, and `docs/development-handoff.md` when resuming the
older local work described there.

Internet radio player and alarm clock. Rust + Tauri v2 behind a plain
HTML/CSS/JS front end — no bundler, no framework, the Tauri API reached
through `window.__TAURI__`.

`README.md` carries the domain reasoning: why ordinary stations play through a
local relay, why the User-Agent it presents matters, how the browse filters
count. Read it before changing playback, browsing or alarm behaviour — it
records measurements that are expensive to redo.

## Commands

```powershell
npm run dev      # tauri dev
npm run build    # NSIS installer under src-tauri/target/release/bundle
```

```powershell
cargo test --manifest-path src-tauri/Cargo.toml -p aerowave-core
cargo test --manifest-path src-tauri/Cargo.toml --workspace
cargo run --manifest-path src-tauri/Cargo.toml --example relaycheck
```

`python tools/package.py --build` builds the portable zip and prints its SHA-256.
It permanently removes existing staging/output files: review the release skill's
artifact-retention steps before running it against existing output.

Use `$release` for a requested release, including the manifest in the separate
Scoop bucket repository linked from `README.md`. `$station-triage` works through why a
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
| `.codex/agents/` | Read-only playback and core-testability reviewers; inherit the session model |
| `.agents/skills/` | Release and station-triage workflows |
| `.codex/hooks.json` | Native hook wiring; requires trust before execution |

## Gotchas

**Tests only run in `core/`.** The app crate links WebView2 and the Win32 GUI
stack, so a test binary built from it will not load outside a real app process;
that is what `test = false` in `Cargo.toml` is for. Anything worth testing goes
in `core/`.

**`tauri dev` watches `src-tauri/` only.** Edits under `src/` are picked up at
the next launch, not on save. In PowerShell,
`(Get-Item src-tauri/src/main.rs).LastWriteTime = Get-Date` forces a rebuild
that reloads both, or restart the dev process.

**An installed copy blocks `npm run dev`.** Both share the identifier
`com.aerowave.radio`, so `tauri-plugin-single-instance` makes the dev build
surface the installed one and exit 0 with no window. Quit the installed copy
first rather than killing it — it may be holding a snooze.

**Settings depend on whether the running copy is portable.** A copy with a
`data` folder beside the exe is portable and uses it; anything else uses
`%APPDATA%\com.aerowave.radio\aerowave.json`. Two nonportable copies share that
AppData file; a portable copy uses its own data directory. Check SETUP before
changing stations or alarms during testing.

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

The scripts and native Codex wiring are tracked. Review and trust the hooks
through `/hooks` before expecting them to run; see `docs/checks.md`. Until the
runtime reports them trusted, run the documented standalone checks explicitly.
Do not claim automated coverage from the presence of a configuration file.

## Conventions

- Inspect the current branch and diff before editing; preserve unrelated
  uncommitted work. Fetch and compare with the remote before preparing work to
  publish, especially when the local checkout is old.
- Scout inline before delegating. Never exceed 16 spawned agents in a workflow,
  counting nested fan-out. The two reviewers are optional tools for relevant
  changes, not a requirement to spawn agents for every edit.
- Deletions require a dry-run manifest of every path and the total size, followed
  by user approval. Send approved deletions to the Recycle Bin, never permanent
  deletion, and never empty it. This applies to deletion inside scripts too.
- Keep private tool identities, personal transcripts and credentials out of
  public repository content and commit metadata; inspect the staged diff and
  follow personal standing rules before committing.
- Use PowerShell 7 for the native helpers. Follow `.gitattributes` and avoid
  unrelated formatting. Documentation-only changes need link and diff checks;
  use the core tests and targeted native verification for behavior changes.

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
- On this laptop, pushes use the GitHub CLI credential helper, clearing the
  multi-valued system helper first:
  `git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push origin main`.
  Check that the active `gh` account owns the remote.
