---
name: release
description: Release Aerowave by synchronizing versions, building and testing the portable package, publishing it to GitHub, and updating its Scoop manifest.
---

# Releasing Aerowave

A release is finished when a user can install it, not when the GitHub release
exists. The Scoop manifest in the separate bucket repository linked from
`README.md` is part of this job, not a follow-up to offer afterwards.

Use this skill when the user requests a release. Confirm the version number
before changing it if the user did not give one. Carry the authorized release
through both repositories; a request to prepare release files alone does not
authorize publishing them. Follow existing user authorization and the standing
deletion rules. Run the commands below from the repository root in PowerShell 7.

## 1. Bump the version

Five files, six fields. `src-tauri/tauri.conf.json` is the one `package.py`
reads, so it decides what the zip is called.

| File | Field |
|---|---|
| `package.json` | `version` |
| `package-lock.json` | `version`, and the one under `packages` for the root package |
| `src-tauri/Cargo.toml` | the `[package]` version, not a dependency's |
| `src-tauri/core/Cargo.toml` | the `[package]` version |
| `src-tauri/tauri.conf.json` | `version` |

Then check the bump landed everywhere:

```powershell
python tools/hooks/check_version.py
```

Silence means the five agree. It will also say if `Cargo.lock` is lagging —
expected until the build in the next step, which is exactly why the build has
to happen before the commit.

## 2. Build

```powershell
npm run build
```

Release build plus the NSIS bundle. It rewrites the two `Cargo.lock` version
fields that only a build touches; commit before this and the lock ships stale.

If cargo is not on PATH, locate the installed toolchain before prepending it.
On this machine the candidates have been
`$env:USERPROFILE/scoop/apps/rustup-gnu/current/.cargo/bin`,
`$env:USERPROFILE/scoop/persist/rustup-gnu/.cargo/bin`, and the matching linker
under `$env:USERPROFILE/scoop/apps/mingw/current/bin`. Confirm the directories
exist and add the selected paths to `$env:PATH` with PowerShell's `;` separator.
Check `cargo --version` before retrying the build.

After the build, require both workspace entries in `src-tauri/Cargo.lock` to
match the release version before committing:

```powershell
python tools/hooks/check_version.py --strict
```

A running installed copy does not block the build, but it does block
`npm run dev` — both share `com.aerowave.radio`, so single-instance surfaces
the installed one and exits 0 with no window. Ask the user to quit it rather
than killing it; it may be holding a snooze.

## 3. Package

Inspect `tools/package.py` before running it: it permanently removes an existing
`dist/Aerowave` staging directory and the ZIP for the current version. Preserve
those artifacts first by moving each existing path to a unique directory under
`dist/retained/` with native PowerShell `Move-Item -LiteralPath`. Resolve and
verify every source and destination stays beneath the repository's `dist`
directory, use unused destinations, and retain both successful and failed runs.
Only run packaging once both paths it would remove are absent.

If cleanup is requested, show a dry-run manifest of every proposed path and the
total size, obtain approval for that manifest, then send those paths to the
Recycle Bin. Do not let the packaging script perform permanent cleanup and do
not empty the bin. Release authorization does not replace cleanup approval.

```powershell
python tools/package.py
```

Produces `dist/Aerowave-<version>-win-x64.zip` and prints the SHA-256. That
hash goes in two places — the release notes and the Scoop manifest — and it
changes on every repack even when nothing inside it does, so take it from this
run and never from a previous one.

## 4. Smoke-test the zip

Extract it to a temp directory and run the exe from there. Its own `data`
folder makes that copy portable, which keeps the test away from the user's
real stations and alarms. Confirm it starts, plays something, and reports the
new version in SETUP. Use `docs/windows-testing.md` for native-window checks;
a browser preview cannot establish that the packaged WebView2 app works.
Retain the extracted test copy until any cleanup has been approved.

## 5. Commit and push

The release convention is `main`, unless the user requests another branch or
review workflow. Inspect both worktrees and stage only release changes. Use a
sentence-case title and a prose body explaining the release. Inspect the staged
diff for private local identities and credentials before committing. Follow the
standing account check and credential-helper rule when pushing each repository:

```powershell
gh auth status
git -c credential.helper= -c credential.helper="!gh auth git-credential" push origin main
```

## 6. Publish the GitHub release

Prepare the complete release notes in a UTF-8 file, set `$releaseNotesPath` to
that file, and replace `X.Y.Z` below with the confirmed version. Check whether
the tag or release already exists
before creating it; report any conflict instead of replacing existing assets
or publishing under another version.

```powershell
gh release create vX.Y.Z dist/Aerowave-X.Y.Z-win-x64.zip --verify-tag --title "Aerowave X.Y.Z" --notes-file $releaseNotesPath
```

Convention: tag `vX.Y.Z`, one asset — the zip, not the installer. Quote the
SHA-256 in the notes; it is what a manual downloader checks against, since the
exe is not code-signed and SmartScreen will name an unknown publisher. Create
the version tag at the verified release commit and push that specific tag with
the same credential helper before running `gh release create --verify-tag`.

## 7. Update the Scoop manifest — this is part of the release

In that Scoop bucket repository, edit `bucket/aerowave.json`: `version`, the download
`url` for the new tag, and `hash` (the SHA-256 from step 3). Commit and push
that repo too.

Then confirm the round trip actually works:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "scoop update aerowave"
```

The execution-policy bypass is needed — plain `powershell -NoProfile` fails
with "running scripts is disabled on this system".

## 8. Report

Say what shipped, both repos that were touched, and the checksum.

If the user has been testing in a dev build, report the actual storage locations:
a copy with a `data` folder beside the exe is portable and uses it; anything else
uses `%APPDATA%\com.aerowave.radio\aerowave.json`. Two nonportable copies share
that AppData file. Stations in a portable test copy do not migrate on their own.
