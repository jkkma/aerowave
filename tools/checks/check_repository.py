"""Run repository regressions and guard the actual changed vendor paths.

The default comparison covers uncommitted changes. Pass --base to include
commits in a branch or pull request; an unavailable base is an error.
"""

import argparse
import os
import pathlib
import subprocess
import sys

import guard_vendor

ROOT = pathlib.Path(__file__).resolve().parents[2]


def git_output(*args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True,
        stdin=subprocess.DEVNULL, timeout=30,
    ).stdout


def changed_paths(base):
    # Resolve before passing the revision to diff, so an invalid base cannot
    # silently become a filename filter or an option. NULs preserve odd names.
    revision = git_output("rev-parse", "--verify", "--end-of-options",
                          base + "^{commit}").decode("ascii").strip()
    outputs = [
        git_output("diff", "--no-ext-diff", "--no-textconv", "--name-only",
                   "--no-renames", "-z", "--cached", revision, "--"),
        git_output("diff", "--no-ext-diff", "--no-textconv", "--name-only",
                   "--no-renames", "-z", "--"),
        git_output("ls-files", "--others", "--exclude-standard", "-z"),
    ]
    # Index and worktree changes are separate: an unstaged reversal must not
    # hide a vendored edit that is still staged. Disabling rename detection
    # keeps both ends of moves into or out of the protected directory.
    return {ROOT / os.fsdecode(name) for output in outputs
            for name in output.split(b"\0") if name}


def check_vendor_changes(base):
    try:
        # Keep Git's lexical paths: a new symlink in src/vendor is protected
        # even if its resolved destination happens to be outside that directory.
        protected = guard_vendor.protected_paths(changed_paths(base))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Cannot inspect changed vendor paths against {base!r}: {error}",
              file=sys.stderr)
        return 2
    if protected:
        names = ", ".join(path.relative_to(ROOT).as_posix() for path in protected)
        print(f"Vendored upstream files changed: {names}. Do not hand-edit them; "
              "a complete upstream replacement and licence need explicit review.",
              file=sys.stderr)
        return 2
    print(f"Vendor guard passed (comparison: {base}, plus staged/untracked edits).")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("AEROWAVE_CHECK_BASE") or "HEAD",
                        help="Git commit/ref to compare (default: AEROWAVE_CHECK_BASE or HEAD)")
    args = parser.parse_args(argv)
    commands = [
        ("Strict versions", ["tools/checks/check_version.py", "--strict"]),
        ("IPC seam", ["tools/checks/check_seam.py"]),
        ("Checker regressions", ["-m", "unittest", "discover", "-s",
                                 "tools/checks/tests", "-p", "test_*.py"]),
    ]
    failed = []
    for label, command in commands:
        print(f"\n== {label} ==", flush=True)
        try:
            result = subprocess.run([sys.executable, "-B", *command], cwd=ROOT,
                                    stdin=subprocess.DEVNULL, timeout=120)
            code = result.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            print(f"Could not run {label}: {error}", file=sys.stderr)
            code = 2
        if code:
            failed.append(label)
    if check_vendor_changes(args.base):
        failed.append("Vendor guard")
    if failed:
        print("Repository checks failed: " + ", ".join(failed), file=sys.stderr)
        return 2
    print("Repository checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
