"""Application configuration, loaded from environment / .env file."""
from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

REPO_ROOT = Path(__file__).resolve().parents[2]

# Override to point at another env file, or at a path that does not exist to
# ignore .env entirely (the test suite does the latter, so a developer's real
# configuration can never leak into a test run).
ENV_FILE = os.getenv("FM_ENV_FILE", str(REPO_ROOT / ".env"))


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- core -------------------------------------------------------------
    app_name: str = "Financial Manager"
    database_url: str = f"sqlite:///{REPO_ROOT / 'data' / 'finance.db'}"
    data_dir: Path = REPO_ROOT / "data"

    # Base URL this app is reachable at. Used to build OAuth redirect URIs.
    public_base_url: str = "http://localhost:8000"

    # Fernet key used to encrypt provider credentials at rest.
    # Generate with:  python -m backend.app.security --new-key
    encryption_key: str = ""

    # Signs session cookies. Rotating it logs everyone out.
    session_secret: str = ""
    session_ttl_hours: int = 24 * 14
    cookie_secure: bool = False

    # --- Revolut Business API --------------------------------------------
    revolut_environment: str = "sandbox"  # "sandbox" | "production"
    revolut_client_id: str = ""
    # Path to the PEM private key whose certificate was uploaded to Revolut.
    revolut_private_key_path: str = ""
    # The domain registered as the redirect URI host in the Revolut portal.
    revolut_redirect_uri: str = ""

    # --- GoCardless Bank Account Data (personal Revolut, Open Banking) ----
    gocardless_secret_id: str = ""
    gocardless_secret_key: str = ""
    gocardless_redirect_uri: str = ""

    # --- AI (Claude) ------------------------------------------------------
    # Leave the key blank to keep every AI feature switched off. Nothing is sent
    # anywhere until this is set.
    anthropic_api_key: str = ""
    ai_model: str = "claude-opus-5"
    ai_effort: str = "medium"            # chat and written insights
    ai_categorise_effort: str = "low"    # merchant classification is simple work
    ai_server_side_fallback: bool = True
    ai_chat_history_turns: int = 20

    # --- behaviour --------------------------------------------------------
    default_currency: str = "GBP"
    initial_sync_days: int = 730
    sync_overlap_days: int = 7

    @property
    def revolut_api_base(self) -> str:
        if self.revolut_environment == "production":
            return "https://b2b.revolut.com/api/1.0"
        return "https://sandbox-b2b.revolut.com/api/1.0"

    @property
    def revolut_authorize_base(self) -> str:
        if self.revolut_environment == "production":
            return "https://business.revolut.com/app-confirm"
        return "https://sandbox-business.revolut.com/app-confirm"

    @property
    def effective_revolut_redirect_uri(self) -> str:
        return self.revolut_redirect_uri or f"{self.public_base_url}/api/connections/revolut-business/callback"

    @property
    def effective_gocardless_redirect_uri(self) -> str:
        return self.gocardless_redirect_uri or f"{self.public_base_url}/api/connections/gocardless/callback"


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    return settings
