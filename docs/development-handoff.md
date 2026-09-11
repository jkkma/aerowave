# Development handoff: 2026-09-09

The initial local checkout was based on `4f26a4c` (0.2.1). Fetching before the
push revealed that remote `main` had advanced to `9efcc12` (0.9.1), including
project agent configuration. The published migration is based on that newer
code. The original local checkout and its app edits were kept intact in a
separate worktree; the feature work below is not part of this migration commit.

Reconcile those edits with the latest code before resuming. Durable project
instructions live in [AGENTS.md](../AGENTS.md), and the native testing notes live
in [windows-testing.md](windows-testing.md).

## Preserved work from the older checkout

The power feature has now been integrated with the 0.10.0 code for the 0.11.0
release (2026-09-11); the list and resume steps below describe the older migration
snapshot. Current behavior is documented in the README. Source validation and
non-disruptive native checks do not establish actual suspend/wake reliability;
that still needs the coordinated test window described in
[windows-testing.md](windows-testing.md).

The pending request is to let the sleep timer suspend or shut down the PC, and
to wake a sleeping PC for alarms. The older local working tree contains:

- `src-tauri/src/power.rs`, an untracked module for Windows suspend/shutdown,
  power-policy inspection, wake timers and keeping the machine awake.
- Cargo dependency updates, Tauri command wiring, scheduler-owned sleep timers,
  wake-timer scheduling and a 45-second wake lead with core tests.
- Persisted `sleepTimerAction` and `wakeForAlarms` settings, the action selector,
  wake-policy messaging, a cancellable power-action countdown and layout changes.
- Earlier accessibility fixes for setting names and the ringing dialog's focus
  trap, plus `Aw-Press` in `tools/drive.ps1` for real key events.

These edits were present before the migration. They have not been ported onto
0.9.1, committed, released or validated by the migration.

## Resume here

1. In the older checkout, review the temporary sleep-timer chip in `src/index.html`: the normal
   15-minute button currently reads `TEST1` with `data-mins="1"`. The previous
   session introduced it for an end-to-end test and stopped during that test.
   Restore the normal control when continuing feature work and before release.
2. Port the intended feature onto the current code, resolving differences in
   playback, alarm handling and sleep-timer behavior. Then run the checks in
   `AGENTS.md`. Keep `power.rs` with the feature changes; the older local
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

The published migration converts the current project instruction file to
`AGENTS.md`, both reviewer agents to `.codex/agents/`, both workflows to
`.agents/skills/`, and the hook wiring to `.codex/hooks.json` with native tool
payload handling. See [checks and hook trust](checks.md) for activation and
standalone verification. There was no project MCP server or model/API dependency
to translate.

The persistent window-testing note is incorporated into `windows-testing.md`;
the older session's unfinished-work context is recorded above. Neither document
requires access to the former agent's storage. Original session archives and
computer-wide settings remain intact.
