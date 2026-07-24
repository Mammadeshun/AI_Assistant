"""Application entrypoint.

Run with:  uvicorn backend.app.main:app --reload
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .config import get_settings
from .db import init_db
from .providers.base import ConsentExpired, ProviderError
from .routers import accounts, analytics, auth, budgeting, connections, transactions

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

FRONTEND_DIR = Path(__file__).resolve().parents[2] / "frontend"


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    settings = get_settings()
    if not settings.encryption_key:
        logging.getLogger(__name__).warning(
            "ENCRYPTION_KEY is not set — bank connections will fail until it is. "
            "Generate one with: python -m backend.app.security --new-key"
        )
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
