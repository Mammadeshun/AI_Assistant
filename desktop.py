"""Entry point for the packaged desktop build.

Everything the launcher script does — keys, port, browser — happens in-process
here, because a packaged app has no Python, no pip and no virtualenv to lean on.
Double-clicking the executable is the whole installation.
"""
from __future__ import annotations

import os
import secrets
import socket
import sys
import threading
import time
import webbrowser
from pathlib import Path

DEFAULT_PORT = 8000


def data_dir() -> Path:
    from backend.app.paths import app_data_dir

    return app_data_dir()


def bundled(name: str) -> Path:
    from backend.app.paths import bundle_dir

    return bundle_dir() / name


def ensure_env_file() -> None:
    """Create .env with fresh secrets on first run.

    Written next to the executable so it survives upgrades and can be edited by
    hand — it's where the user's API keys go later.
    """
    env_path = data_dir() / ".env"
    if env_path.exists():
        return

    from cryptography.fernet import Fernet

    template_path = bundled(".env.example")
    template = template_path.read_text(encoding="utf-8") if template_path.exists() else ""
    if not template:
        template = "ENCRYPTION_KEY=\nSESSION_SECRET=\n"

    template = template.replace(
        "ENCRYPTION_KEY=", f"ENCRYPTION_KEY={Fernet.generate_key().decode()}", 1
    )
    template = template.replace(
        "SESSION_SECRET=", f"SESSION_SECRET={secrets.token_urlsafe(48)}", 1
    )
    env_path.write_text(template, encoding="utf-8")
    print(f"Created {env_path}")


def free_port(preferred: int) -> int:
    for candidate in range(preferred, preferred + 20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("127.0.0.1", candidate))
                return candidate
            except OSError:
                continue
    return preferred


def local_address() -> str | None:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()


def open_browser_when_ready(url: str, port: int) -> None:
    for _ in range(120):
        time.sleep(0.5)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.5)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                try:
                    webbrowser.open(url)
                except Exception:  # noqa: BLE001 - not worth failing the app over
                    pass
                return


def main() -> None:
    # .env has to exist before settings are read, since it holds the keys.
    ensure_env_file()
    os.environ.setdefault("FM_ENV_FILE", str(data_dir() / ".env"))

    port = int(os.environ.get("PORT") or free_port(DEFAULT_PORT))
    os.environ["PORT"] = str(port)

    print()
    print("  Financial Manager")
    print()
    print(f"  On this computer:  http://localhost:{port}")
    address = local_address()
    if address:
        print(f"  From your phone:   http://{address}:{port}")
        print("                     ^ type this into the Android app")
    print()
    print("  Your browser should open by itself in a moment.")
    print("  Keep this window open — closing it stops the app.")
    if sys.platform == "win32":
        print()
        print("  If Windows asks about the firewall, click Allow.")
        print("  Without it your phone cannot reach this app.")
    print()

    threading.Thread(target=open_browser_when_ready, args=(f"http://localhost:{port}", port), daemon=True).start()

    import uvicorn

    from backend.app.main import app

    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n  Stopped.")
    except Exception as exc:  # noqa: BLE001 - a packaged app must not vanish silently
        print()
        print("  Something went wrong starting the app:")
        print(f"    {type(exc).__name__}: {exc}")
        print()
        print("  Send this message and the lines above to whoever set this up.")
        print()
        if sys.platform == "win32":
            # Otherwise the console window closes instantly and the error is lost.
            input("  Press Enter to close...")
        raise SystemExit(1)
