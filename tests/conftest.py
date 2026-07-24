from __future__ import annotations

import os
import tempfile
from pathlib import Path

import pytest
from cryptography.fernet import Fernet

# Configure the app before anything imports settings.
_TMP = Path(tempfile.mkdtemp(prefix="fm-tests-"))
os.environ.update(
    {
        # Ignore any real .env: tests define their own world.
        "FM_ENV_FILE": str(_TMP / "no-such-env"),
        "DATABASE_URL": f"sqlite:///{_TMP / 'test.db'}",
        "DATA_DIR": str(_TMP),
        "ENCRYPTION_KEY": Fernet.generate_key().decode(),
        "SESSION_SECRET": "test-session-secret",
        "PUBLIC_BASE_URL": "http://testserver",
        "REVOLUT_ENVIRONMENT": "sandbox",
        "REVOLUT_CLIENT_ID": "test-client",
        "GOCARDLESS_SECRET_ID": "test-id",
        "GOCARDLESS_SECRET_KEY": "test-key",
    }
)

from fastapi.testclient import TestClient  # noqa: E402

from backend.app.db import SessionLocal, engine  # noqa: E402
from backend.app.main import app  # noqa: E402
from backend.app.models import Base  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_database():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def db():
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def authed_client(client):
    client.post(
        "/api/auth/register",
        json={
            "email": "me@example.com",
            "password": "a-long-enough-password",
            "display_name": "Test",
            "base_currency": "GBP",
        },
    ).raise_for_status()
    return client


@pytest.fixture
def user_id(authed_client):
    return authed_client.get("/api/auth/me").json()["id"]
