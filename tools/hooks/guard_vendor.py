"""Keep hand edits out of src/vendor/.

Those files are upstream minified builds with their licence beside them, and
the content security policy is the reason they are vendored at all rather than
fetched. A local edit to one is invisible in review and lost at the next
upgrade: replace the file wholesale instead.
"""

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
VENDOR = ROOT / "src" / "vendor"


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0

    raw = (payload.get("tool_input") or {}).get("file_path")
    if not raw:
        return 0
    try:
        target = pathlib.Path(raw).resolve()
        target.relative_to(VENDOR)
    except (ValueError, OSError):
        return 0

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": (
                f"{target.name} is a vendored upstream build in src/vendor/. Replace "
                "the whole file with a new upstream release and update the licence "
                "beside it, rather than editing it in place."
            ),
        }
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
