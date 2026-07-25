"""Application configuration, loaded from environment / .env file."""
from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

from .paths import app_data_dir

# Where the user's own files live. Running from source this is the repository;
# in a packaged build it is the folder holding the executable.
REPO_ROOT = app_data_dir()

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

    # When the app is on the public internet, an open sign-up form means the
    # first stranger to find the URL claims the instance. Set this and the
    # sign-up form requires it. Ignored when empty (fine on a home network).
    signup_token: str = ""
    # Failed sign-in attempts allowed per email before a cool-off.
    login_max_attempts: int = 8
    login_lockout_seconds: int = 300

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
    # Haiku 4.5 by default: cheapest model that handles this work well, and it
    # keeps a small amount of API credit going a long way. Set AI_MODEL to
    # claude-opus-5 for noticeably sharper answers on vague questions.
    ai_model: str = "claude-haiku-4-5"
    # Effort is ignored on models that don't support it (Haiku among them).
    ai_effort: str = "medium"            # chat and written insights
    ai_categorise_effort: str = "low"    # merchant classification is simple work
    ai_server_side_fallback: bool = True
    ai_chat_history_turns: int = 20

    # --- automatic syncing -------------------------------------------------
    # Keeps connected accounts current without anyone pressing Sync. 12 hours
    # stays comfortably inside GoCardless's ~4 reads per account per day.
    auto_sync_enabled: bool = True
    auto_sync_interval_hours: int = 12
    # How often the loop looks for work (not how often it syncs).
    auto_sync_check_seconds: int = 900
    auto_sync_start_delay_seconds: int = 120

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


def normalise_database_url(url: str) -> str:
    """Make a hosting provider's DATABASE_URL usable by SQLAlchemy.

    Managed Postgres is usually handed out as `postgres://…`, a scheme
    SQLAlchemy dropped support for. Rewriting it here means the URL can be
    pasted from the provider's dashboard unchanged.
    """
    if url.startswith("postgres://"):
        return "postgresql+psycopg://" + url[len("postgres://") :]
    if url.startswith("postgresql://"):
        return "postgresql+psycopg://" + url[len("postgresql://") :]
    return url


@lru_cache
def get_settings() -> Settings:
    settings = Settings()

    settings.database_url = normalise_database_url(settings.database_url)

    # Render (and most PaaS hosts) publish the app's own public URL at runtime.
    # Picking it up automatically keeps OAuth redirect URIs correct without the
    # user having to paste the URL back into the config after the first deploy.
    external_url = os.getenv("RENDER_EXTERNAL_URL")
    if external_url and settings.public_base_url == "http://localhost:8000":
        settings.public_base_url = external_url.rstrip("/")

    # Anything reached over HTTPS should be issuing secure cookies.
    if settings.public_base_url.startswith("https://"):
        settings.cookie_secure = True

    if settings.database_url.startswith("sqlite"):
        settings.data_dir.mkdir(parents=True, exist_ok=True)
    return settings
