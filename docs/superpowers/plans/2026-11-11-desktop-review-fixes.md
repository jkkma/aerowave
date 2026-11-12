# Desktop Review Fixes Implementation Plan

> **Execution:** Follow AGENTS.md and record checks in the local plan ledger.

**Goal:** Resolve six desktop and shared defects found in the review of Aerowave 0.16.7.

**Architecture:** Keep validation and portable parsing in the core crate. Apply stream destination policy at native request boundaries and keep delayed frontend work scoped to the state or media load that started it.

**Scope:** Windows and shared desktop paths. Android-specific implementation and device validation are deferred. Use the current checkout and preserve unrelated changes. Review local diffs before any integration.

## Playback transport

Ownership covers `src-tauri/src/stream.rs`, `relay.rs`, `hls.rs`, and their core networking and playlist code.

- [x] Public-to-private redirects and playlist hops. Add a fixture that verifies a public source cannot cause a local request while an intentionally local station remains usable. Carry the original access decision through resolution, redirects, playlist hops, and the legacy ICY fallback. Verify DNS answers before connecting. Require affirmative native approval for any desktop direct-media fallback.
- [x] HLS variables. Add manifest cases for variable definitions and references in segment and URI attributes. Resolve and validate supported references through the same guarded HLS route, and fail unresolved references explicitly. Scope imports and query values to declared playlist relationships.

## Live setup and backup validation

Ownership covers `src-tauri/src/store.rs`, `backup.rs`, the persistence commands in `lib.rs`, and `src-tauri/core/src/backup.rs`.

- [x] Reproduce invalid HTTP(S) station URLs and a setup that grows beyond the backup size limit. Verify valid station edits and repair of legacy entries still work.
- [x] Validate new or changed station addresses and cap setup growth at the live write boundary. Keep the normal backup format compatible and preserve a lossless local copy before restoring over a legacy setup the export encoder cannot represent.
- [x] Run the core backup regressions and check the desktop restore command against the core recovery decisions. Validate the normalized restore candidate before writing recovery or settings files.

## Frontend request ordering

Ownership covers `src/app.js` and the corresponding frontend tests.

- [x] Settings. Reproduce a startup reflection response arriving after a newer edit and a failed settings save with newer input. Preserve pending preferences and apply the authoritative startup field without reloading unrelated settings.
- [x] Desktop audio. Reproduce a media error beginning direct fallback before the original play promise rejects. Associate play callbacks with the media load that created them. Verify direct fallback can still report its own failure and retry, and that stop, pause, and source replacement still cancel stale work.
- [x] Folder status. Reproduce delayed counts replacing a newer folder selection or refresh. Guard backup, shuffle, and alarm editor folder results by the selection that requested them.

## Integrated verification

- [x] Run the focused regressions before and after each change.
- [x] Run `npm test`, the locked workspace cargo check, and version, IPC seam, vendor, and diff checks on the combined change.
- [x] Review the transport changes against the playback measurements in README and the data changes against existing persistence and recovery behavior.
