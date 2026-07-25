"""Application entrypoint.

Run with:  uvicorn backend.app.main:app --reload
"""
from __future__ import annotations

import logging
import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .config import get_settings
from .db import init_db
from .providers.base import ConsentExpired, ProviderError
from .routers import accounts, ai, analytics, auth, budgeting, connections, transactions

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

FRONTEND_DIR = Path(__file__).resolve().parents[2] / "frontend"


def lan_address() -> str | None:
    """This machine's address on the local network.

    Printed at startup because it is the one thing the phone app needs and the
    one thing that isn't obvious: "localhost" on a phone means the phone.
    Opening a UDP socket to a public address is the portable way to ask the OS
    which local interface it would route out of — no packet is actually sent.
    """
    import socket

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()


def running_port() -> str:
    """The port this process is actually serving on.

    `--port` on the command line wins over the PORT variable, so read argv first
    — printing an address the app isn't listening on would be worse than
    printing nothing.
    """
    argv = sys.argv
    for index, argument in enumerate(argv):
        if argument == "--port" and index + 1 < len(argv):
            return argv[index + 1]
        if argument.startswith("--port="):
            return argument.split("=", 1)[1]
    return os.getenv("PORT", "8000")


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    settings = get_settings()
    log = logging.getLogger(__name__)

    if not settings.encryption_key:
        log.warning(
            "ENCRYPTION_KEY is not set — bank connections will fail until it is. "
            "Generate one with: python -m backend.app.security --new-key"
        )

    port = running_port()
    address = lan_address()
    if address:
        log.info("On this computer:      http://localhost:%s", port)
        log.info("From your phone:       http://%s:%s   <- use this in the app", address, port)
    else:
        log.info("On this computer: http://localhost:%s", port)
        log.info("Could not detect a network address — is this machine offline?")
    yield


app = FastAPI(
    title="Financial Manager",
    description="Self-hosted personal finance manager with Revolut connectivity.",
    version="1.0.0",
    lifespan=lifespan,
)

app.include_router(auth.router)
app.include_router(connections.router)
app.include_router(accounts.router)
app.include_router(transactions.router)
app.include_router(budgeting.router)
app.include_router(analytics.router)
app.include_router(ai.router)


@app.exception_handler(ConsentExpired)
async def consent_expired_handler(_: Request, exc: ConsentExpired) -> JSONResponse:
    return JSONResponse(
        status_code=409,
        content={"detail": str(exc), "action": "reconnect"},
    )


@app.exception_handler(ProviderError)
async def provider_error_handler(_: Request, exc: ProviderError) -> JSONResponse:
    return JSONResponse(status_code=502, content={"detail": str(exc)})


@app.get("/api/health", tags=["meta"])
def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "revolut_environment": settings.revolut_environment,
        "encryption_configured": bool(settings.encryption_key),
    }


if FRONTEND_DIR.is_dir():
    app.mount("/static", StaticFiles(directory=FRONTEND_DIR), name="static")

    @app.get("/", include_in_schema=False)
    def index() -> FileResponse:
        return FileResponse(FRONTEND_DIR / "index.html")

    @app.get("/sw.js", include_in_schema=False)
    def service_worker() -> FileResponse:
        # Must be served from the root for the worker to control the whole app;
        # a worker at /static/ would only ever see /static/ requests.
        return FileResponse(
            FRONTEND_DIR / "sw.js",
            media_type="application/javascript",
            headers={"Service-Worker-Allowed": "/", "Cache-Control": "no-cache"},
        )

    @app.get("/manifest.webmanifest", include_in_schema=False)
    def manifest() -> FileResponse:
        return FileResponse(
            FRONTEND_DIR / "manifest.webmanifest", media_type="application/manifest+json"
        )
