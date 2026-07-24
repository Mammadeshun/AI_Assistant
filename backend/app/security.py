"""Password hashing, session tokens and credential encryption at rest."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
from typing import Any

from cryptography.fernet import Fernet, InvalidToken

from .config import get_settings

_PBKDF2_ROUNDS = 240_000


# --------------------------------------------------------------------------
# Passwords
# --------------------------------------------------------------------------
def hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, _PBKDF2_ROUNDS)
    return f"pbkdf2_sha256${_PBKDF2_ROUNDS}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algorithm, rounds, salt_hex, digest_hex = stored.split("$")
    except ValueError:
        return False
    if algorithm != "pbkdf2_sha256":
        return False
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), bytes.fromhex(salt_hex), int(rounds)
    )
    return hmac.compare_digest(digest.hex(), digest_hex)


# --------------------------------------------------------------------------
# Opaque session tokens (stored server-side, hashed)
# --------------------------------------------------------------------------
def new_session_token() -> str:
    return secrets.token_urlsafe(48)


def hash_session_token(token: str) -> str:
    secret = get_settings().session_secret or "insecure-dev-secret"
    return hmac.new(secret.encode(), token.encode(), hashlib.sha256).hexdigest()


# --------------------------------------------------------------------------
# Credential encryption
# --------------------------------------------------------------------------
class CredentialCipher:
    """Encrypts provider credentials (OAuth tokens etc.) before they touch disk."""

    def __init__(self, key: str | None = None) -> None:
        key = key or get_settings().encryption_key
        if not key:
            raise RuntimeError(
                "ENCRYPTION_KEY is not set. Generate one with:\n"
                "    python -m backend.app.security --new-key"
            )
        self._fernet = Fernet(key.encode() if isinstance(key, str) else key)

    def encrypt(self, payload: dict[str, Any]) -> str:
        raw = json.dumps(payload, separators=(",", ":"), default=str).encode()
        return self._fernet.encrypt(raw).decode()

    def decrypt(self, blob: str | None) -> dict[str, Any]:
        if not blob:
            return {}
        try:
            return json.loads(self._fernet.decrypt(blob.encode()))
        except (InvalidToken, ValueError) as exc:
            raise RuntimeError(
                "Stored credentials could not be decrypted. "
                "The ENCRYPTION_KEY has probably changed; reconnect the account."
            ) from exc


def generate_key() -> str:
    return Fernet.generate_key().decode()


def redact(value: str | None, keep: int = 4) -> str:
    if not value:
        return ""
    if len(value) <= keep:
        return "*" * len(value)
    return "*" * (len(value) - keep) + value[-keep:]


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode(), b.encode())


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


if __name__ == "__main__":  # pragma: no cover - tiny CLI helper
    import sys

    if "--new-key" in sys.argv:
        print(generate_key())
    elif "--new-secret" in sys.argv:
        print(secrets.token_urlsafe(48))
    else:
        print("usage: python -m backend.app.security [--new-key|--new-secret]")
