# Automated checks and native qualification

Run the repeatable, non-device regression suites from the repository root:

```sh
npm test
```

Use Node.js 24, Python 3.12 (available as `python`), Git, and a current stable
Rust toolchain. The tests use Node and Python's standard libraries; `npm ci` is
only needed for the Tauri development/build commands, not these regressions.
Rust dependencies come from the committed Cargo lockfile. A missing toolchain
is a failed check, never an implicit skip.

The individual commands are:

```sh
npm run test:frontend
npm run check:repository
npm run test:core
```

- **Frontend:** every `tools/tests/*.test.cjs` file runs through Node's test
  runner. The harness models DOM, IPC and playback events; it does not start a
  browser, WebView, native player or audio device. The quoted glob is expanded
  by Node, including on Windows.
- **Repository:** strict version consistency (including Cargo.lock), IPC
  registration, Python checker regressions, and a vendor guard based on real
  changed paths. The command reports all repository failures in one run.
- **Rust core:** `aerowave-core` tests with `--locked`, using the existing
  toolchain-discovery and process-tree timeout helper. Native GUI dependencies
  do not belong in this test binary. The helper's 170-second deadline suits
  local runs; for a cold dependency download/build, run the Cargo command
  below directly. CI uses its own longer job deadline.

```sh
cargo test --manifest-path src-tauri/Cargo.toml --locked -p aerowave-core
```

`npm test` stops at the first failed suite. If Rust is unavailable, run the
frontend and repository commands separately, and report Rust as not run rather
than calling that partial result a complete pass.

## Comparing changed vendor paths

By default the repository command compares to `HEAD` and includes staged,
unstaged and untracked changes. To validate committed work on a branch too:

```sh
npm run check:repository -- --base origin/main
```

Use the intended comparison commit, or set `AEROWAVE_CHECK_BASE`. The guard
checks both sides of renames and does not let an unstaged reversal hide an edit
that remains staged. NUL-delimited Git output preserves spaces and newlines in
filenames. It fails if Git cannot resolve the base, rather than silently
checking an empty set. No command stages, deletes or commits files.

Any change under `src/vendor/` blocks this automated guard. An intentional
upstream upgrade needs a separately reviewed whole-file replacement and its
licence; a passing path check alone would not establish upstream provenance.

## GitHub Actions

[`checks.yml`](../.github/workflows/checks.yml) runs on pull requests, pushes to
`main`, and manual dispatch. Manual runs require a comparison ref and default
to `HEAD^`. Pull requests use their base commit; pushes use the event's previous
commit. Full history is fetched for the vendor comparison. An unavailable or
all-zero comparison commit fails closed and must be replaced with a real base
in a manual run.

The workflow separates these jobs on Ubuntu 24.04 and Windows Server 2022:

1. Frontend and repository regressions, using Node 24 and Python 3.12.
2. Rust core tests on stable Rust, with the committed lockfile.
3. Native workspace `cargo check`, with Linux development headers installed on
   the hosted runner. This verifies platform-specific Rust compilation and the
   Tauri command boundary, but does not link an installer, launch the app or
   qualify playback.

Check jobs have bounded timeouts, read-only repository permissions and no
retained checkout credentials. Actions are pinned to release commits. No
signing keys or connected devices are used. An added workflow is not a passing
CI run: verify the hosted result after the branch is published. Branch-protection
settings are managed separately.

### One-time Windows 0.16.0 prerelease

The separately authorized publication job runs only for a push to this
repository's `main`, only while the app version is exactly `0.16.0`, and only
after every frontend/repository, Rust core and native-compile matrix job has
succeeded for that same commit. The pushed head commit message must start with
`Release Aerowave 0.16.0 prerelease`; ordinary later main commits do not attempt
publication. Pull requests and manual check runs cannot publish. Future versions
require deliberate workflow/helper changes; deliberately retrying publication
of `0.16.0` fails on an existing tag or release rather than replacing either.

The hosted Windows Server 2022 image supplies Visual Studio 2022 with MSVC C++
build tools and Windows SDK, Git, PowerShell 7, GitHub CLI and rustup. The job
selects stable x64 MSVC Rust and Python 3.12, then builds the real release
executable with `cargo build --release --locked --features tauri/custom-protocol
--bin aerowave`. The explicit dependency feature enables Tauri's production
protocol configuration, matching a CLI release build. Tauri's tracked frontend
is embedded directly, so the portable ZIP build does not need an npm
install, NSIS installer build or signing material. `STATIC_VCRUNTIME=true` asks
Tauri to link the Visual C++ runtime statically for the portable executable.
Hosted-runner prerequisites
are recorded in the [Windows image manifest](https://github.com/actions/runner-images/blob/main/images/windows/Windows2022-Readme.md).

`tools/checks/release_windows.py` checks the executable's Windows file/product
version and packages only when the staging directory, versioned ZIP and
validation receipt are absent. It never invokes `package.py` against existing
output. ZIP validation requires exactly the x64 EXE, x64 WebView2 loader DLL,
README, licence and portable `data/README.txt`, verifies CRCs, and compares each
member to its build/source bytes. The actual ZIP SHA-256 and exact source
commit are appended to [the 0.16.0 notes](releases/0.16.0.md).

Only this publication job gets `contents: write` through the built-in
`GITHUB_TOKEN`. It creates `v0.16.0` once at the checked commit, creates a draft
prerelease, uploads the ZIP without clobbering, downloads it into a fresh
retained directory and verifies the checksum. It verifies the tag again before
publishing the populated prerelease, with `make_latest: false`. No empty binary
release is published. A failure after creating the tag/draft leaves them for
inspection and stops; it does not delete, retag, overwrite assets, silently
reuse a draft, or promote an incomplete release. Main-push runs are not
automatically cancelled by later pushes during publication.

This ZIP is an **unsigned Windows prerelease**. Packaged-window startup,
audible radio/folder/emergency-tone playback, hidden-window scheduling and
alarm controls on a real Windows device still need a smoke test. Android
JVM/signing/device work remains separate and incomplete; this job publishes no
Android debug or unsigned substitute. It does not update the stable Scoop
manifest or claim release qualification for untested native behavior.

## Android and physical-device gates

The default workflow does **not** run Android JVM/Gradle tests. They require the
Android SDK/JDK and Tauri-generated Gradle metadata described in
[Android setup](android.md). In an already prepared Android checkout, run the
plugin's full debug unit suite from `src-tauri/gen/android`:

```powershell
.\gradlew.bat :tauri-plugin-android-audio:testDebugUnitTest
```

Use `./gradlew` on Unix. Preserve the tracked Android project; do not regenerate
it over application changes just to obtain the ignored Tauri metadata. JVM and
Robolectric results are useful regression evidence, but do not prove audible
alarm output, physical shake-to-snooze, Bluetooth routing or OEM background
delivery. Signed Android packaging and same-certificate upgrades are a separate
[release procedure](android-release.md).

Before claiming native or release qualification, follow the platform records:

- [Windows testing](windows-testing.md): actual WebView2 playback, hidden-window
  scheduled alarms, sleep/wake, native input and persisted-state recovery.
- [Linux testing](linux-testing.md): WebKitGTK/GStreamer playback, tray and
  autostart, actual audio output, and scheduled alarms in the native window.
- [Android qualification](android-qualification.md): cold-process and locked
  scheduled starts, physical dismiss/snooze gestures, audible source and
  fallback behavior, Do Not Disturb/OEM policy, and sustained/overnight runs.

Keep the exact app revision, device/OS, conditions, observation window and
remaining gaps with each result. Passing unit tests or compilation cannot
establish unattended alarm reliability on a user's Windows PC or Android phone.
