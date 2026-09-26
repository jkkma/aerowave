# Android releases

Android requires the same signing certificate to replace an installed
application and rejects a lower version code. Distributed updates use a higher
version code. The release key is therefore part of Aerowave's update identity.
Losing it means future builds cannot update an installed release with
application ID `com.aerowave.radio`.

These steps create and use local signing material. They do not publish an APK,
install it on a device, or change the desktop release.

## Initialize signing once

Run this from the repository root in PowerShell 7:

```powershell
pwsh -NoProfile -File tools/android-signing-init.ps1
```

The initializer creates a 4096-bit RSA key in a password-protected PKCS#12
keystore below `%LOCALAPPDATA%\Aerowave\signing`. The randomly generated
password is protected with Windows DPAPI for the current user, and the directory
ACL is restricted to that user. Neither the password nor the key is written to
the repository, a command line, a log, or a global environment variable.

Initialization is idempotent only for a complete, valid key set. If the
directory already exists but its manifest, credential, keystore, alias or
certificate does not match, the script stops without regenerating or replacing
anything. Restore the original directory from backup rather than creating a new
key under the same application identity.

`AEROWAVE_ANDROID_SIGNING_ROOT` can point to another private directory for the
current PowerShell process. Keep that setting local to the process; the path is
not a secret, but machine-specific configuration does not belong in the repo.

## Build and retain a signed APK

```powershell
pwsh -NoProfile -File tools/android-build.ps1 -Release -Target aarch64
```

The helper decrypts the password only for the build, passes signing values to a
single-use Gradle process through temporary process environment variables, and
restores any previous values in a `finally` block. Release minification and the
Rust release profile remain enabled.

The Gradle settings resolve the native audio plugin relative to this checkout.
Cached Tauri metadata can contain an absolute path from a different checkout;
that path must not select the plugin source used for tests or release builds.

Gradle derives the Android version code from the stable semantic version using
`major * 1,000,000 + minor * 1,000 + patch`. For example, version `0.11.5` is
code `11005`. Minor and patch values are limited to 999, and the build fails if
Tauri's generated code disagrees. Never lower the semantic version for a build
that should update an already distributed APK: Android rejects version-code
downgrades.

After the build, the helper runs `apksigner verify --verbose --print-certs`,
requires the APK certificate to match the persistent local key, and calculates
the APK SHA-256. It also requires the APK to contain Aerowave's native library
for exactly the ABI selected by `-Target`, with no stale ABI carried over from
another build. It uses the SDK command-line tools' `apkanalyzer` to verify that
the packaged DEX still contains the public, non-static `getPluginManager()` JNI
entry point. The tracked ProGuard rule preserves it even in fresh checkouts
without Tauri's generated rules; removing it causes a crash before the UI loads.
This artifact check does not replace launching the signed build on a device.
The helper copies the APK into a new, timestamped directory below
`dist/android-release/` and writes `manifest.json` beside it with the package
identity, version, target and Android ABI, source commit, hashes and certificate
fingerprint. Existing release artifacts are never overwritten or removed.

The certificate SHA-256 is public identification data and may be recorded in
release notes. The keystore, its password and `credentials.dpapi` are private.

## Back up and recover the key

Back up the complete signing directory to private, access-controlled storage.
The DPAPI credential can only be decrypted by the same Windows user profile, so
that folder backup alone is insufficient after an account or machine loss.
Store the keystore password separately in a password manager. To transfer it
without printing it, run:

```powershell
pwsh -NoProfile -File tools/android-signing-copy-password.ps1
```

The helper places the password on the clipboard, waits while it is saved, then
clears the clipboard if its contents are unchanged. Do this in a private session
and complete the password-manager save before pressing Enter.

Recovery requires the original `aerowave-release.p12`, the password, and the
same alias recorded in `signing-manifest.json`. On a new Windows profile, restore
the keystore and manifest to the signing directory but leave
`credentials.dpapi` absent, then run:

```powershell
pwsh -NoProfile -File tools/android-signing-recover.ps1
```

The recovery helper reads the password as a secure prompt, verifies the
keystore's certificate against the manifest, protects a new credential with the
current user's DPAPI, and restricts the directory ACL. It refuses to replace an
existing credential. Do not run initialization over a partial restore. A changed
fingerprint creates a different Android application identity even when the
package name is unchanged.

## Moving from the debug preview

Debug and release currently use the production application ID
`com.aerowave.radio`, but Android's generated debug key and Aerowave's release
key have different certificates. A release APK therefore cannot update the
existing debug installation; `adb install -r` reports an incompatible
certificate. The build helper does not uninstall or modify a connected device.

Installing the release on that device requires a deliberate uninstall of the
debug application first. Uninstalling erases the application's private Android
settings, including saved stations and volume, so preserve anything needed by
hand before doing it. Once the release is installed, later releases use the
retained key; distributed updates carry a higher version code.

## Read-only reliability monitor

After starting playback by hand on the intended release build and choosing the
speaker or Bluetooth route to test, a bounded eight-hour monitor can collect
read-only evidence without changing playback, device permissions, the network,
or screen state:

```powershell
pwsh -NoProfile -File tools/android-monitor.ps1 -Serial <adb-serial>
```

The defaults are 480 minutes and one sample per minute. Use
`-DurationMinutes 1 -IntervalSeconds 5` for a short validation run. Each run
gets a new UTC-stamped directory below `dist/evidence/android-soak/`; an
explicit `-OutputDirectory` must not already exist. Ctrl+C leaves the samples
already written and the latest running summary. It records the summary as
interrupted as well when PowerShell can unwind the script normally.

For an unattended Windows run, add `-KeepComputerAwake`. While that monitor
process is running, it asks Windows to prevent automatic system sleep without
waking the display or changing a persistent power plan. The request is released
when the script finishes or unwinds, and its use is recorded in `run.json`.

The monitor records filtered Aerowave package, PID, UID and MediaSession state,
plus minimal power, battery, music-route and AudioFlinger evidence. It retains
JSONL and one concise text file per sample, then writes JSON and text summaries.
It never stores logcat or complete global audio/media dumps. A MediaSession
position that advances is only reported state and may be extrapolated. It is not
evidence that audio decoded. A matching active AudioFlinger track is stronger
renderer evidence when the device exposes one, and the raw filtered PID rows are
kept so that claim can be reviewed. Unknown positions, non-positive reported
speeds and a repeated publication timestamp are retained as non-comparable
telemetry instead of being reported as playback failures.
