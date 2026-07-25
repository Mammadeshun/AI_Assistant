"""The one-command launcher.

This is the first thing anyone runs, often on a machine with nothing set up, so
its failure modes matter more than most code in the app.
"""
from __future__ import annotations

import importlib.util
import socket
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def load_launcher():
    """Import start.py by path — it deliberately isn't part of the package."""
    spec = importlib.util.spec_from_file_location("start_launcher", ROOT / "start.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


launcher = load_launcher()


def test_launcher_uses_only_the_standard_library():
    """It has to run before anything is installed, so no third-party imports."""
    source = (ROOT / "start.py").read_text(encoding="utf-8")
    third_party = ("import fastapi", "import uvicorn", "import sqlalchemy", "import pydantic")
    assert not any(name in source for name in third_party)


def test_free_port_returns_the_preferred_port_when_available():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        spare = probe.getsockname()[1]
    assert launcher.free_port(spare) == spare


def test_free_port_steps_over_a_busy_port():
    """A second copy of the app, or anything else on 8000, must not be fatal."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as taken:
        taken.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        taken.bind(("127.0.0.1", 0))
        taken.listen(1)
        busy = taken.getsockname()[1]

        chosen = launcher.free_port(busy)
        assert chosen != busy
        assert busy < chosen <= busy + 20


def test_venv_python_path_matches_the_platform():
    path = launcher.venv_python()
    if sys.platform == "win32":
        assert path.name == "python.exe" and path.parent.name == "Scripts"
    else:
        assert path.name == "python" and path.parent.name == "bin"


def test_old_python_is_refused_with_an_actionable_message(monkeypatch, capsys):
    """Python 3.9 must not produce a confusing traceback pages later."""
    monkeypatch.setattr(launcher.sys, "version_info", (3, 9, 0))
    with pytest.raises(SystemExit):
        launcher.check_python()

    output = capsys.readouterr().out
    assert "3.11" in output
    assert "python.org" in output


def test_current_python_is_accepted():
    launcher.check_python()  # must not raise on a supported interpreter


def test_windows_batch_file_exists_and_checks_for_python():
    """Windows users get a double-click path, and a clear message without Python."""
    batch = (ROOT / "start-windows.bat").read_text(encoding="utf-8")
    assert "python start.py" in batch
    assert "Add python.exe to PATH" in batch
    assert "pause" in batch  # so a failure stays on screen instead of vanishing
