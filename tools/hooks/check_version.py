"""Refuse to end a turn with the version bumped in some files but not others.

The version lives in five files, and nothing in the build fails when they
disagree - the installer just ships a number that its own About box and the
Scoop manifest contradict. `tauri.conf.json` is treated as the truth because
that is the one `tools/package.py` reads when it names the zip.

Runs as a Stop hook, not on each edit: mid-bump the files legitimately
disagree, and only a bump left unfinished at the end of a turn is a mistake.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
TAURI = ROOT / "src-tauri"


def json_version(path, *keys):
    """Read a nested key out of a JSON file. `keys` is the path to it."""
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in keys:
        data = data[key]
    return data


def cargo_package_version(path):
    """The [package] version, not the version of some dependency below it."""
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            section = stripped
        elif section == "[package]":
            match = re.match(r'version\s*=\s*"([^"]+)"', stripped)
            if match:
                return match.group(1)
    return None


def cargo_lock_versions(path):
    """The two entries cargo rewrites for this workspace's own crates."""
    found = {}
    name = None
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        match = re.match(r'name\s*=\s*"([^"]+)"', stripped)
        if match:
            name = match.group(1)
            continue
        match = re.match(r'version\s*=\s*"([^"]+)"', stripped)
        if match and name in ("aerowave", "aerowave-core"):
            found[name] = match.group(1)
            name = None
    return found


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        payload = {}
    # A second pass would block on the same thing forever if the first could
    # not fix it.
    if payload.get("stop_hook_active"):
        return 0

    want = json_version(TAURI / "tauri.conf.json", "version")

    others = {
        "package.json": json_version(ROOT / "package.json", "version"),
        "package-lock.json (version)": json_version(ROOT / "package-lock.json", "version"),
        "package-lock.json (packages.\"\")": json_version(
            ROOT / "package-lock.json", "packages", "", "version"
        ),
        "src-tauri/Cargo.toml": cargo_package_version(TAURI / "Cargo.toml"),
        "src-tauri/core/Cargo.toml": cargo_package_version(TAURI / "core" / "Cargo.toml"),
    }

    wrong = {name: got for name, got in others.items() if got != want}
    if wrong:
        lines = [
            f"Version mismatch. src-tauri/tauri.conf.json says {want}, but:",
            "",
        ]
        lines += [f"  {name}: {got}" for name, got in wrong.items()]
        lines += [
            "",
            "All five files carry the version and nothing fails the build when they",
            "disagree. Set them all to the same value before finishing.",
        ]
        print("\n".join(lines), file=sys.stderr)
        return 2

    # Cargo.lock is only rewritten by a build, so a lag here is a reminder
    # rather than a mistake - but it has to be gone before the commit.
    lock = cargo_lock_versions(TAURI / "Cargo.lock")
    stale = {name: got for name, got in lock.items() if got != want}
    if stale:
        listed = ", ".join(f"{name} {got}" for name, got in stale.items())
        print(json.dumps({
            "systemMessage": (
                f"Cargo.lock still at {listed} while the source says {want}. "
                "Run a build before committing - cargo rewrites those two fields."
            )
        }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
