"""Resolve file arguments relative to the caller's working directory."""

import pathlib


def resolve_paths(raw_paths):
    base = pathlib.Path.cwd()
    return {(base / raw).resolve() for raw in raw_paths}
