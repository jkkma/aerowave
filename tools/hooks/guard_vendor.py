"""Keep hand edits out of src/vendor/.

Those files are upstream minified builds with their licence beside them, and
the content security policy is the reason they are vendored at all rather than
fetched. A local edit to one is invisible in review and lost at the next
upgrade: replace the file wholesale instead.
"""

import argparse
import json
import pathlib
import sys

from hook_input import edited_paths, read_event

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
VENDOR = ROOT / "src" / "vendor"


def protected_paths(paths):
    return sorted(path for path in paths if path.is_relative_to(VENDOR))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hook", action="store_true", help="Read a Codex event from stdin")
    parser.add_argument("paths", nargs="*", help="Paths to check before editing")
    args = parser.parse_args(argv)
    if not args.hook and not args.paths:
        parser.error("provide paths to check, or --hook")
    try:
        paths = edited_paths(read_event()) if args.hook else {
            pathlib.Path(raw).resolve() for raw in args.paths
        }
    except (ValueError, OSError) as error:
        print(f"Cannot check vendor paths: {error}", file=sys.stderr)
        return 2
    targets = protected_paths(paths)
    if targets:
        reason = (
            "Vendored upstream files in src/vendor/ must not be hand-edited: "
            + ", ".join(str(path.relative_to(ROOT)) for path in targets)
            + ". Replace the complete upstream build and update its licence for an upgrade."
        )
        if args.hook:
            print(json.dumps({"hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            }}))
            return 0
        print(reason, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
