# Development handoff: 2026-09-09

This is a snapshot of unfinished work at the switch to Codex. Recheck the current
diff before resuming; it is not a claim that these changes are released or fully
tested. Durable project instructions live in [AGENTS.md](../AGENTS.md), and the
native testing notes live in [windows-testing.md](windows-testing.md).

## Current feature work

The pending request is to let the sleep timer suspend or shut down the PC, and
to wake a sleeping PC for alarms. The working tree already contains:

- `src-tauri/src/power.rs`, an untracked module for Windows suspend/shutdown,
  power-policy inspection, wake timers and keeping the machine awake.
- Cargo dependency updates, Tauri command wiring, scheduler-owned sleep timers,
  wake-timer scheduling and a 45-second wake lead with core tests.
- Persisted `sleepTimerAction` and `wakeForAlarms` settings, the action selector,
  wake-policy messaging, a cancellable power-action countdown and layout changes.
- Earlier accessibility fixes for setting names and the ringing dialog's focus
  trap, plus `Aw-Press` in `tools/drive.ps1` for real key events.

These edits were present before the migration and were preserved. The migration
did not commit, release or validate the feature implementation.

## Resume here

1. Review the temporary sleep-timer chip in `src/index.html`: the normal
   15-minute button currently reads `TEST1` with `data-mins="1"`. The previous
   session introduced it for an end-to-end test and stopped during that test.
   Restore the normal control when continuing feature work and before release.
2. Run the core tests and compile checks documented in `AGENTS.md` against the
   current diff. Keep `power.rs` with the other feature changes when committing;
   `lib.rs` already imports it.
3. Verify timer expiry, cancellation, page reload recovery and alarm precedence
   in the running app. Check the countdown's keyboard focus and confirm that a
   ringing alarm is not silenced or interrupted by the sleep timer.
4. Complete controlled native checks of suspend/wake behavior and AC/DC wake
   policy handling. A reported supported policy is not proof that this machine
   resumes successfully. Actual power actions need a coordinated test window.
5. Recheck the earlier setting-name and ringing-dialog focus fixes with real
   input. The prior session reported DOM-level checks, but its final native
   verification was blocked by the screensaver taking the input desktop.
6. Update the README for the completed power features and rebuild the intended
   distribution after verification. Do not assume the current portable binary
   reflects the working tree. Review packaging's deletion behavior first.

## Migration coverage

At the original handoff, this checkout had no local agent instruction file,
custom commands, skills, hooks,
MCP configuration or model/API dependency to translate. Its persistent window
testing note has been incorporated into `docs/windows-testing.md`, updated to
use the existing `Aw-Press` helper. The recent session's unfinished-work context
is recorded above. Neither document requires access to the former agent's
storage. Original session archives and computer-wide settings remain intact.

The subsequent migration brought the native reviewers, explicit release and
station-triage skills, and four hook scripts into this checkout from the later
Codex migration. Their instructions match this branch's actual app: it has
direct playback and `probe_stream`, with no relay or HLS implementation. The
application history was not merged and the feature edits above were preserved.
Read [checks.md](checks.md) for hook trust and standalone validation.

The version check exposed an existing inconsistency: both root versions in
`package-lock.json` were `0.1.0`, while the source manifests say `0.2.1`. The
migration synchronized only those two metadata fields, preserving the dependency
lock and all unfinished feature edits. The strict version check now passes.
