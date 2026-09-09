"""Run the aerowave-core tests after an edit to the crate they cover.

They take about a second and they are the only tests in the project that can
run at all, so there is no reason to defer them to a build. Nothing happens
for edits anywhere else.
"""

import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CORE = ROOT / "src-tauri" / "core"

# rustup-gnu is installed through Scoop and is not always on the PATH a hook
# inherits, so fall back to where Scoop puts it.
FALLBACKS = [
    pathlib.Path.home() / "scoop" / "apps" / "rustup-gnu" / "current" / ".cargo" / "bin",
    pathlib.Path.home() / "scoop" / "persist" / "rustup-gnu" / ".cargo" / "bin",
    pathlib.Path.home() / ".cargo" / "bin",
]


def find_cargo():
    found = shutil.which("cargo")
    if found:
        return found, None
    for directory in FALLBACKS:
        for name in ("cargo.exe", "cargo"):
            candidate = directory / name
            if candidate.is_file():
                return str(candidate), str(directory)
    return None, None


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0

    tool_input = payload.get("tool_input") or {}
    raw = (payload.get("tool_response") or {}).get("filePath") or tool_input.get("file_path")
    if not raw:
        return 0
    try:
        edited = pathlib.Path(raw).resolve()
        edited.relative_to(CORE)
    except (ValueError, OSError):
        return 0
    if edited.suffix != ".rs":
        return 0

    cargo, extra_path = find_cargo()
    if cargo is None:
        print("cargo not found, skipping aerowave-core tests", file=sys.stderr)
        return 0

    env = dict(os.environ)
    if extra_path:
        env["PATH"] = extra_path + os.pathsep + env.get("PATH", "")

    # The gnu toolchain needs the mingw linker whether or not cargo itself was
    # on the PATH we inherited - the two are installed separately and either
    # can be missing on its own.
    mingw = pathlib.Path.home() / "scoop" / "apps" / "mingw" / "current" / "bin"
    if mingw.is_dir():
        env["PATH"] = str(mingw) + os.pathsep + env.get("PATH", "")

    result = subprocess.run(
        [cargo, "test", "-p", "aerowave-core"],
        cwd=ROOT / "src-tauri",
        env=env,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        print(
            "aerowave-core tests failed after this edit:\n\n"
            + "\n".join(tail[-40:]),
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
