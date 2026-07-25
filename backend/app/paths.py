"""Where things live, whether running from source or from a bundled .exe.

A PyInstaller build unpacks its read-only files into a temporary folder that is
deleted on exit, so anything the user must keep — the database, their .env —
has to be written somewhere else. These two functions are the only place that
distinction is expressed.
"""
from __future__ import annotations

import sys
from pathlib import Path


def is_frozen() -> bool:
    """True when running from a packaged executable rather than source."""
    return getattr(sys, "frozen", False)


def bundle_dir() -> Path:
    """Read-only files shipped with the app (the frontend, .env.example).

    When frozen these live in PyInstaller's extraction directory; from source
    they're simply in the repository.
    """
    if is_frozen():
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parents[2]


def app_data_dir() -> Path:
    """Files the user owns and must keep between runs.

    Beside the executable, so someone can see their data, back it up, or move
    the whole thing to another machine by copying one folder.
    """
    if is_frozen():
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[2]
