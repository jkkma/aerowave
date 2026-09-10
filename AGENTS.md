# AeroWave development instructions

Read `README.md` for the product behavior, build prerequisites and repository map.
Read `docs/development-handoff.md` when resuming unfinished work and
`docs/windows-testing.md` before testing the native window. Native Codex reviewers
live in `.codex/agents/`, reusable workflows in `.agents/skills/`, and hook wiring
in `.codex/hooks.json`. Read `docs/checks.md` for hook trust and manual checks.

## Working agreements

- Inspect `git status` and the relevant diff before editing. Preserve existing
  uncommitted work and keep changes scoped to the request.
- Use PowerShell 7 for the Windows helpers. Prefer `rg` for searches and targeted
  patches for edits. Follow `.gitattributes`: PowerShell files use CRLF; other
  text uses LF. Avoid unrelated formatting.
- Scout inline first. Keep each workflow within 16 spawned agents, including
  nested fan-out; calculate the maximum before delegating. The playback and
  core-testability reviewers are optional for relevant changes; they do not
  delegate further and inherit the current session model.
- Deletions require a dry-run manifest of every path and total size, followed by
  user approval. Send approved deletions to the Recycle Bin and never empty it.
  This applies to deletion inside scripts too: `tools/package.py` currently
  removes existing staging/output files permanently, so review that behavior
  before running it against existing output.
- Keep private local tool identities, personal transcripts and credentials out
  of repository content and commit metadata. Inspect the staged diff before a
  public commit and follow any additional personal rules.

## Architecture and behavior to preserve

- This is a Windows desktop radio player and alarm clock built with Rust and
  Tauri v2. `src/` is plain HTML, CSS and JavaScript served directly from disk;
  use `window.__TAURI__`. There is no frontend framework or bundler.
- Keep Three.js vendored under `src/vendor/` with its license. Preserve offline
  operation, stock Windows fonts and the existing content security policy.
- Put pure parsing and scheduling policy, with unit tests, in
  `src-tauri/core/`. Tauri commands, networking, persistence and Windows APIs
  belong in `src-tauri/src/`.
- Alarm, snooze and sleep-timer deadlines belong in Rust. WebView2 throttles
  timers in hidden windows; the frontend displays state and plays audio.
  Preserve the startup/reload handshake that recovers pending alarms.
- HTTP success does not prove a station is playable. Test decoding in the real
  media engine, preserve playlist resolution and backup-folder fallback, and
  fail unsupported media promptly. There is no synthesized fallback tone.
- Keep the orb decorative. Routing cross-origin radio through Web Audio can
  silence playback. Local audio uses individually granted asset-protocol paths;
  keep the initial asset scope empty.
- Preserve portable `data/` detection beside the executable and the fallback to
  `%APPDATA%\com.aerowave.radio`. Keep serialized, atomic JSON writes and backward
  compatibility with existing settings, including unfamiliar preserved values.
- Preserve keyboard accessibility, named controls and modal focus handling.
  Use `Aw-Press` for keyboard behavior checks; `Aw-Key` and `Aw-Type` use
  `SendKeys` and can produce false failures. See `docs/windows-testing.md`.

## Commands and verification

Run these from the repository root:

```powershell
npm install
npm run dev
cargo test --manifest-path src-tauri/Cargo.toml --workspace
cargo check --manifest-path src-tauri/Cargo.toml --workspace
npm run build
```

Choose verification appropriate to the change; the commands above are not an
instruction to build installers for every edit. There is no configured npm test
or lint script. The app crate intentionally disables its test harnesses because
of WebView2/Win32 linkage; unit-test pure logic in `aerowave-core` and check native
integration in the running app. Documentation-only changes need link and diff
checks. Report checks actually run and any remaining limits.

The helper scripts for window testing and portable packaging are documented in
`README.md` and `docs/windows-testing.md`. A successful build does not establish
audio decoding, alarm recovery, keyboard behavior or hardware wake support.

## Native Codex workflows

Use `$release` for an authorized release, including its Scoop manifest in the
separate bucket linked from `README.md`. Use `$station-triage` to diagnose playback
through this checkout's `probe_stream` command and native media engine. This
checkout has no `relay.rs`, `hls.rs`, `browse.rs` or `relaycheck` example; do not
import assumptions from newer application versions without reading the code.

Review and trust the four hook definitions through `/hooks` before relying on
automatic enforcement. The patch hooks protect vendored upstream files and run
core tests for affected core paths. The Stop hooks check version consistency
and literal IPC command registration. They do not cover arbitrary shell edits;
use the standalone commands in `docs/checks.md` for those changes. An untrusted
hook is not evidence that any check ran.

The version checker includes both root fields in `package-lock.json`, the two
Cargo manifests and `tauri.conf.json`; `--strict` also checks the workspace
entries in `Cargo.lock`. Report pre-existing failures separately from migration
validation and preserve unfinished feature work.
