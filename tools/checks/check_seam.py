"""Check frontend IPC commands and events against the Rust implementation.

A command the front end calls has to appear in the `generate_handler!` list in
`lib.rs`, and an event the front end listens for has to be emitted from Rust.
Neither is a compile error: the button simply does nothing, and the failure
shows up in a running app rather than in a build.
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
APP_JS = ROOT / "src" / "app.js"
LIB_RS = ROOT / "src-tauri" / "src" / "lib.rs"
RUST_SRC = ROOT / "src-tauri" / "src"


def invoked():
    """Command names app.js passes to invoke() as a literal string."""
    text = APP_JS.read_text(encoding="utf-8", errors="replace")
    return set(re.findall(r'invoke\(\s*"([A-Za-z0-9_]+)"', text))


def registered():
    """Names inside the generate_handler! list, which is what Tauri exposes."""
    text = LIB_RS.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"generate_handler!\s*\[(.*?)\]", text, re.S)
    if not match:
        return None
    body = re.sub(r"//[^\n]*", "", match.group(1))
    return {name.strip() for name in body.split(",") if name.strip()}


def listened():
    text = APP_JS.read_text(encoding="utf-8", errors="replace")
    return set(re.findall(r'listen\(\s*"([A-Za-z0-9_-]+)"', text))


def emitted():
    names = set()
    for path in sorted(RUST_SRC.glob("*.rs")):
        text = path.read_text(encoding="utf-8", errors="replace")
        names |= set(re.findall(r'\.emit(?:_to)?\(\s*"([A-Za-z0-9_-]+)"', text))
    return names


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)

    handlers = registered()
    if handlers is None:
        print("No generate_handler! list found in src-tauri/src/lib.rs.", file=sys.stderr)
        return 2

    calls = invoked()
    unregistered = sorted(calls - handlers)
    if unregistered:
        print(
            "These commands are invoked from src/app.js but are not in the\n"
            "generate_handler! list in src-tauri/src/lib.rs, so they will fail at\n"
            "runtime with no compile error:\n\n"
            + "\n".join(f"  {name}" for name in unregistered),
            file=sys.stderr,
        )
        return 2

    heard = listened()
    sent = emitted()
    notes = []
    unheard = sorted(heard - sent)
    if unheard:
        notes.append(
            "listened for in app.js but never emitted from Rust: " + ", ".join(unheard)
        )
    if notes:
        message = "IPC seam: " + "; ".join(notes)
        print(message, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
