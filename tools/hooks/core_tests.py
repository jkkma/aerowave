"""Run the aerowave-core tests after an edit to the crate they cover.

They take about a second and they are the only tests in the project that can
run at all, so there is no reason to defer them to a build. Nothing happens
for edits anywhere else.
"""

import argparse
import os
import pathlib
import shutil
import subprocess
import sys

from hook_input import edited_paths, read_event

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


def touches_core(paths):
    return any(path.is_relative_to(CORE) and
               (path.suffix == ".rs" or path.name == "Cargo.toml") for path in paths)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hook", action="store_true", help="Read a Codex event from stdin")
    parser.add_argument("paths", nargs="*", help="Only run if these edits affect the core crate")
    args = parser.parse_args(argv)
    try:
        paths = edited_paths(read_event()) if args.hook else {
            pathlib.Path(raw).resolve() for raw in args.paths
        }
    except (ValueError, OSError) as error:
        print(f"Cannot inspect core edits: {error}", file=sys.stderr)
        return 2
    if (args.hook or args.paths) and not touches_core(paths):
        return 0

    cargo, extra_path = find_cargo()
    if cargo is None:
        print("cargo not found; install Rust before validating aerowave-core", file=sys.stderr)
        return 2

    env = dict(os.environ)
    if extra_path:
        env["PATH"] = extra_path + os.pathsep + env.get("PATH", "")

    # The gnu toolchain needs the mingw linker whether or not cargo itself was
    # on the PATH we inherited - the two are installed separately and either
    # can be missing on its own.
    mingw = pathlib.Path.home() / "scoop" / "apps" / "mingw" / "current" / "bin"
    if mingw.is_dir():
        env["PATH"] = str(mingw) + os.pathsep + env.get("PATH", "")

    try:
        result = subprocess.run(
            [cargo, "test", "-p", "aerowave-core"],
            cwd=ROOT / "src-tauri",
            env=env,
            capture_output=True,
            text=True,
            timeout=170,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"Could not complete aerowave-core tests: {error}", file=sys.stderr)
        return 2
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        print(
            "aerowave-core tests failed after this edit:\n\n"
            + "\n".join(tail[-40:]),
            file=sys.stderr,
        )
        return 2
    if not args.hook:
        print(result.stdout.strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
