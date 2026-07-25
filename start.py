#!/usr/bin/env python3
"""One-command launcher. Works on Windows, macOS and Linux.

    python start.py

Sets up everything the app needs the first time, then starts it and opens your
browser. Safe to run again — it skips whatever is already done.

Deliberately uses nothing but the standard library, because it has to run
*before* anything is installed.
"""
from __future__ import annotations

import os
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VENV = ROOT / ".venv"
MIN_PYTHON = (3, 11)
DEFAULT_PORT = 8000


# --------------------------------------------------------------------------
# Pretty output — plain text, no dependencies
# --------------------------------------------------------------------------
def supports_colour() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if sys.platform == "win32":
        return os.environ.get("WT_SESSION") or os.environ.get("TERM_PROGRAM")
    return sys.stdout.isatty()


COLOUR = supports_colour()


def paint(text: str, code: str) -> str:
    return f"\033[{code}m{text}\033[0m" if COLOUR else text


def step(message: str) -> None:
    print(paint("→ ", "36") + message, flush=True)


def ok(message: str) -> None:
    print(paint("✓ ", "32") + message, flush=True)


def fail(message: str, fix: str | None = None) -> None:
    print()
    print(paint("✗ " + message, "31"), flush=True)
    if fix:
        print()
        print(fix, flush=True)
    print()
    sys.exit(1)


# --------------------------------------------------------------------------
# Setup steps
# --------------------------------------------------------------------------
def check_python() -> None:
    if sys.version_info < MIN_PYTHON:
        current = ".".join(str(part) for part in sys.version_info[:3])
        wanted = ".".join(str(part) for part in MIN_PYTHON)
        fail(
            f"This needs Python {wanted} or newer. You're running {current}.",
            "Install a newer Python from https://www.python.org/downloads/\n"
            "On Windows, tick 'Add python.exe to PATH' during installation.",
        )


def venv_python() -> Path:
    if sys.platform == "win32":
        return VENV / "Scripts" / "python.exe"
    return VENV / "bin" / "python"


def create_venv() -> None:
    if venv_python().exists():
        return
    step("Creating a private Python environment (one-off)")
    result = subprocess.run(
        [sys.executable, "-m", "venv", str(VENV)], capture_output=True, text=True
    )
    if result.returncode != 0 or not venv_python().exists():
        detail = (result.stderr or result.stdout).strip()
        fix = (
            "On Debian or Ubuntu this usually means the venv module is missing:\n"
            "    sudo apt install python3-venv"
            if sys.platform.startswith("linux")
            else "Try reinstalling Python from https://www.python.org/downloads/"
        )
        fail(f"Could not create the environment.\n{detail}", fix)


def install_dependencies() -> None:
    marker = VENV / ".dependencies-installed"
    requirements = ROOT / "requirements.txt"
    if marker.exists() and marker.stat().st_mtime >= requirements.stat().st_mtime:
        return

    step("Installing dependencies — a few minutes the first time, then never again")
    commands = [
        [str(venv_python()), "-m", "pip", "install", "--upgrade", "pip", "--quiet"],
        [str(venv_python()), "-m", "pip", "install", "-r", str(requirements), "--quiet"],
    ]
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()[-1500:]
            fail(
                f"Installing dependencies failed.\n\n{detail}",
                "If it mentions a network or proxy problem, check this machine's\n"
                "internet connection and run this script again.",
            )
    marker.touch()
    ok("Dependencies installed")


def create_env_file() -> None:
    env = ROOT / ".env"
    if env.exists():
        return

    step("Creating your .env with fresh encryption keys")
    sys.path.insert(0, str(ROOT))
    generate = [
        str(venv_python()),
        "-c",
        "from cryptography.fernet import Fernet; import secrets;"
        "print(Fernet.generate_key().decode()); print(secrets.token_urlsafe(48))",
    ]
    result = subprocess.run(generate, capture_output=True, text=True)
    if result.returncode != 0:
        fail("Could not generate the encryption keys.\n" + result.stderr.strip())

    key, secret = result.stdout.strip().splitlines()[:2]
    template = (ROOT / ".env.example").read_text(encoding="utf-8")
    template = template.replace("ENCRYPTION_KEY=", f"ENCRYPTION_KEY={key}", 1)
    template = template.replace("SESSION_SECRET=", f"SESSION_SECRET={secret}", 1)
    env.write_text(template, encoding="utf-8")
    ok(".env created — your API keys go in this file later")


def free_port(preferred: int) -> int:
    """First usable port at or after the preferred one."""
    for candidate in range(preferred, preferred + 20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("127.0.0.1", candidate))
                return candidate
            except OSError:
                continue
    fail(
        f"Ports {preferred} to {preferred + 19} are all in use.",
        "Close whatever is using them, or set a port: PORT=9000 python start.py",
    )
    return preferred  # unreachable, keeps type checkers happy


def open_browser_when_ready(url: str, port: int) -> None:
    """Open the browser once the server actually answers, not before."""
    for _ in range(60):
        time.sleep(0.5)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.5)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                try:
                    webbrowser.open(url)
                except Exception:  # noqa: BLE001 - a headless box has no browser
                    pass
                return


def main() -> None:
    print()
    print(paint("  Financial Manager", "1"))
    print()

    check_python()
    create_venv()
    install_dependencies()
    create_env_file()

    port = int(os.environ.get("PORT") or free_port(DEFAULT_PORT))
    url = f"http://localhost:{port}"

    print()
    ok(f"Starting at {paint(url, '1;36')}")
    print("  Your browser should open by itself. If not, paste that address in.")
    print("  Leave this window open — closing it stops the app.")
    print(paint("  Press Ctrl+C to stop.", "2"))
    print()

    threading.Thread(target=open_browser_when_ready, args=(url, port), daemon=True).start()

    command = [
        str(venv_python()),
        "-m",
        "uvicorn",
        "backend.app.main:app",
        "--host",
        "0.0.0.0",
        "--port",
        str(port),
    ]
    try:
        subprocess.run(command, cwd=str(ROOT))
    except KeyboardInterrupt:
        pass
    print()
    ok("Stopped.")


if __name__ == "__main__":
    main()
