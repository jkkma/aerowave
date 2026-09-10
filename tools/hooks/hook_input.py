"""Read Codex hook events and the paths in an apply_patch request."""

import json
import pathlib
import re
import sys


def read_event():
    # Native events are UTF-8 even when Windows' default text code page is not.
    payload = json.load(getattr(sys.stdin, "buffer", sys.stdin))
    if not isinstance(payload, dict):
        raise ValueError("Hook input must be a JSON object")
    return payload


def edited_paths(payload):
    """Include every patch target, including both sides of a rename.

    Codex reports apply_patch text in tool_input.command. Resolve relative
    targets against the event cwd, since sessions may start below the repo root.
    File-oriented tools can also supply a file_path directly.
    """
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        if payload.get("tool_name") == "apply_patch":
            raise ValueError("apply_patch requires an object tool_input")
        return set()
    raw_paths = []
    if isinstance(tool_input.get("file_path"), str):
        raw_paths.append(tool_input["file_path"])
    if payload.get("tool_name") == "apply_patch":
        patch = tool_input.get("command")
        if not isinstance(patch, str):
            raise ValueError("apply_patch requires a string tool_input.command")
        targets = re.findall(
            r"^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$",
            patch, re.MULTILINE,
        )
        if not targets:
            raise ValueError("apply_patch command contains no recognized file targets")
        raw_paths.extend(targets)
    cwd = payload.get("cwd")
    if cwd is not None and not isinstance(cwd, str):
        raise ValueError("Hook cwd must be a string")
    base = pathlib.Path(cwd or pathlib.Path.cwd())
    return {(base / raw.strip()).resolve() for raw in raw_paths if raw.strip()}
