# Repository checks

[`.codex/hooks.json`](../.codex/hooks.json) connects the checks in
[`tools/hooks/`](../tools/hooks/) to the Codex lifecycle. Python 3.9+ and Git must
be on `PATH`; core tests also require Rust and its linker. Commands resolve the
repository root with Git so starting Codex in a subdirectory also works.

## Enable the hooks

Open the repository in Codex, trust its project configuration, and use `/hooks`
in the CLI to review the four hook definitions. Codex skips new or changed
definitions until they are trusted. The project does not bypass that review or
change your personal settings. See the [official hook documentation](https://learn.chatgpt.com/docs/hooks)
for discovery, trust and the event contract. These definitions were checked
against Codex CLI 0.153.4.

## What runs

| Event | Check | Result |
| --- | --- | --- |
| Before `apply_patch` | `guard_vendor.py` | Rejects patches touching `src/vendor/`, including additions, deletions and either side of a move. Upgrade by replacing a complete upstream build and its licence. |
| After `apply_patch` | `core_tests.py` | Runs `cargo test -p aerowave-core` after changes to Rust files or `Cargo.toml` inside the core crate. Checks every file in a multi-file patch. Missing Cargo or a failed/timed-out test reports a failure. |
| Turn stop | `check_version.py` | Rejects inconsistent source versions; warns about stale or missing workspace entries in `Cargo.lock`. |
| Turn stop | `check_seam.py` | Rejects literal frontend command calls absent from `generate_handler!`; warns about listeners with no matching Rust emission. |

The two stop checks may run concurrently. Each skips a second stop-hook
continuation to avoid an endless retry when a problem cannot be fixed in the
current task. A warning still requires investigation before committing.

The patch hooks cover Codex's `apply_patch` tool, including nested tool calls.
They do not inspect arbitrary shell commands, external editors or every MCP
write tool. For those edits, run the checks below explicitly. These hooks are
development guardrails, not a security boundary.

The IPC check is a static scan of the repository's current literal call
conventions. It does not replace native tests or resolve computed command/event
names, macros or arbitrary JavaScript/Rust syntax. A successful build still
does not establish playback, wake or keyboard behavior.

## Run checks directly

Run from the repository root. Direct mode never waits for JSON on stdin:

```powershell
python -B tools/hooks/guard_vendor.py src/app.js
python -B tools/hooks/core_tests.py
python -B tools/hooks/check_version.py --strict
python -B tools/hooks/check_seam.py
python -B -m unittest discover -s tools/hooks/tests -v
node --test tools/tests/*.test.cjs
cargo run --manifest-path src-tauri/Cargo.toml --example networkcheck --locked
```

The frontend tests run the actual application functions in an isolated DOM/media
harness, covering delayed IPC, source replacement, station TEST readiness, HLS
ranges, browse resets and keyboard event bubbling. They do not establish native
WebView2 decoding or Windows keyboard delivery.

`networkcheck` starts local fixture servers and exercises the production relay
and HLS client without opening the app or its settings. It checks ICY stripping,
redirect/address validation and byte ranges, then waits for the actual 8-second
response-head and 30-second idle-stream deadlines. It makes no broadcaster calls.

The core-test hook owns its subprocess tree. On Windows a job object is attached
before Cargo can start, so a timeout terminates test descendants too. Hook JSON
and Git's repository path are decoded as UTF-8 regardless of the Windows code page.

Pass every proposed target to `guard_vendor.py` before editing through another
tool. With no paths, `core_tests.py` runs the core suite; with paths, it runs
only when at least one affects the core crate. Run it after core edits through
any tool. Run both final checks before committing, using `--strict` for version
validation so stale or missing workspace lock entries fail. Refresh the lock
with the normal Cargo check/build when needed; do not hand-edit dependency
versions.

Hook mode is explicit: `--hook` reads one Codex event from stdin. Blocking
vendor decisions use the documented JSON response; failing post-edit or stop
checks exit with code 2. Successful stop checks emit a JSON object.
