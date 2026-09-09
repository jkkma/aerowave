---
name: release
description: Cut an Aerowave release — bump the version everywhere, build, package, publish the GitHub release, and update the Scoop manifest so the new version is actually installable.
disable-model-invocation: true
---

# Releasing Aerowave

A release is finished when a user can install it, not when the GitHub release
exists. That means the Scoop manifest in the separate `jkkma/scoop-ayylmao`
repo is part of this job, not a follow-up to offer afterwards.

Confirm the version number with the user before starting if they did not give
one. After that, carry the whole sequence through without stopping to ask
again — including the second repo.

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

```bash
python tools/hooks/check_version.py < /dev/null
```

Silence means the five agree. It will also say if `Cargo.lock` is lagging —
expected until the build in the next step, which is exactly why the build has
to happen before the commit.

## 2. Build

```bash
npm run build
```

Release build plus the NSIS bundle. It rewrites the two `Cargo.lock` version
fields that only a build touches; commit before this and the lock ships stale.

If cargo is not on the PATH, prepend the toolchain:

```bash
export PATH="$HOME/scoop/apps/rustup-gnu/current/.cargo/bin:$HOME/scoop/apps/mingw/current/bin:$PATH"
```

`$HOME/scoop/persist/rustup-gnu/.cargo/bin` is the other place it has lived.

A running installed copy does not block the build, but it does block
`npm run dev` — both share `com.aerowave.radio`, so single-instance surfaces
the installed one and exits 0 with no window. Ask the user to quit it rather
than killing it; it may be holding a snooze.

## 3. Package

```bash
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
new version in SETUP.

## 5. Commit and push

Straight to `main` — no branch, no PR. Sentence-case title, then a prose body
giving the reasoning rather than a bulleted list of changes. Push to
`origin main`.

## 6. Publish the GitHub release

```bash
gh release create vX.Y.Z dist/Aerowave-X.Y.Z-win-x64.zip --title "Aerowave X.Y.Z"
```

Convention: tag `vX.Y.Z`, one asset — the zip, not the installer. Quote the
SHA-256 in the notes; it is what a manual downloader checks against, since the
exe is not code-signed and SmartScreen will name an unknown publisher.

## 7. Update the Scoop manifest — this is part of the release

In `jkkma/scoop-ayylmao`, edit `bucket/aerowave.json`: `version`, the download
`url` for the new tag, and `hash` (the SHA-256 from step 3). Commit and push
that repo too.

Then confirm the round trip actually works:

```bash
powershell -NoProfile -ExecutionPolicy Bypass -Command "scoop update aerowave"
```

The execution-policy bypass is needed — plain `powershell -NoProfile` fails
with "running scripts is disabled on this system".

## 8. Report

Say what shipped, both repos that were touched, and the checksum.

If the user has been testing in a dev build, remind them that a dev build and
the Scoop copy keep separate settings: a copy with a `data` folder beside the
exe is portable and uses it, anything else uses
`%APPDATA%\com.aerowave.radio\aerowave.json`. Stations added while testing
live in whichever one was running and do not migrate on their own.
