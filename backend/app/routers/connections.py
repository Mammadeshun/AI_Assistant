"""Bank connection lifecycle: start a link, handle the callback, sync, disconnect."""
from __future__ import annotations

import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import HTMLResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import get_db
from ..deps import current_user, settings_dep
from ..models import Connection, ConnectionStatus, OAuthState, User
from ..providers import gocardless as gc
from ..providers import revolut_business as rb
from ..providers.base import ProviderError
from ..schemas import ConnectionOut, StartLinkRequest, StartLinkResponse, SyncResponse
from ..security import CredentialCipher
from ..services.sync import sync_all, sync_connection

router = APIRouter(prefix="/api/connections", tags=["connections"])

STATE_TTL_MINUTES = 20


def _new_state(db: Session, user_id: int, provider: str, connection_id: int | None) -> str:
    state = secrets.token_urlsafe(24)
    db.add(
        OAuthState(
            user_id=user_id,
            state=state,
            provider=provider,
            connection_id=connection_id,
            expires_at=datetime.now(timezone.utc) + timedelta(minutes=STATE_TTL_MINUTES),
        )
    )
    db.commit()
    return state


def _consume_state(db: Session, state: str, provider: str) -> OAuthState:
    record = db.scalar(select(OAuthState).where(OAuthState.state == state))
    if record is None or record.provider != provider:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown or reused authorisation state.")
    expires_at = record.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    if expires_at < datetime.now(timezone.utc):
        db.delete(record)
        db.commit()
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Authorisation link expired — start again.")
    db.delete(record)
    db.commit()
    return record


def _callback_page(title: str, message: str, ok: bool) -> HTMLResponse:
    colour = "#2e7d32" if ok else "#c62828"
    return HTMLResponse(
        f"""<!doctype html><meta charset="utf-8">
<title>{title}</title>
<style>
 body{{font:16px/1.5 system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0;
       background:#0f1115;color:#e7e9ee}}
 .card{{max-width:30rem;padding:2rem;border-radius:14px;background:#171a21;border:1px solid #262b36;
        text-align:center}}
 h1{{color:{colour};font-size:1.25rem;margin:0 0 .75rem}}
 a{{color:#6aa9ff}}
</style>
<div class="card"><h1>{title}</h1><p>{message}</p>
<p><a href="/">Back to your dashboard</a></p></div>""",
        status_code=200 if ok else 400,
    )


@router.get("", response_model=list[ConnectionOut])
def list_connections(
    user: User = Depends(current_user), db: Session = Depends(get_db)
) -> list[Connection]:
    return list(
        db.scalars(
            select(Connection)
            .where(Connection.user_id == user.id)
            .order_by(Connection.created_at.desc())
        ).all()
    )


@router.get("/providers")
def available_providers(settings: Settings = Depends(settings_dep)) -> list[dict]:
    """Tells the UI which connection routes are actually configured."""
    return [
        {
            "key": "gocardless",
            "name": "Revolut personal (Open Banking)",
            "description": (
                "For a normal Revolut retail account. Connects read-only through the "
                "GoCardless Bank Account Data API; you approve it in the Revolut app. "
                "Consent lasts 90 days."
            ),
            "configured": bool(settings.gocardless_secret_id and settings.gocardless_secret_key),
            "missing": [
                name
                for name, value in (
                    ("GOCARDLESS_SECRET_ID", settings.gocardless_secret_id),
                    ("GOCARDLESS_SECRET_KEY", settings.gocardless_secret_key),
                )
                if not value
            ],
        },
        {
            "key": "revolut_business",
            "name": "Revolut Business API",
            "description": (
                "For a Revolut Business account. Uses Revolut's own API with a "
                "certificate you upload in the business portal."
            ),
            "configured": bool(settings.revolut_client_id and settings.revolut_private_key_path),
            "missing": [
                name
                for name, value in (
                    ("REVOLUT_CLIENT_ID", settings.revolut_client_id),
                    ("REVOLUT_PRIVATE_KEY_PATH", settings.revolut_private_key_path),
                )
                if not value
            ],
        },
        {
            "key": "csv",
            "name": "CSV statement import",
            "description": "Upload a statement exported from the Revolut app. Always available.",
            "configured": True,
            "missing": [],
        },
    ]


# --------------------------------------------------------------------------
# Revolut personal, via GoCardless Open Banking
# --------------------------------------------------------------------------
@router.get("/gocardless/institutions")
async def gocardless_institutions(
    country: str = Query(default="GB", min_length=2, max_length=2),
    _: User = Depends(current_user),
    settings: Settings = Depends(settings_dep),
) -> list[dict]:
    try:
        institutions = await gc.list_institutions(settings, country)
    except ProviderError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
    return [
        {"id": item["id"], "name": item["name"], "logo": item.get("logo")}
        for item in institutions
    ]


@router.post("/gocardless/start", response_model=StartLinkResponse)
async def gocardless_start(
    payload: StartLinkRequest,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> StartLinkResponse:
    institution_id = payload.institution_id or gc.revolut_institution_for(payload.country)

    connection = Connection(
        user_id=user.id,
        provider="gocardless",
        label=payload.label or "Revolut",
        institution_id=institution_id,
        status=ConnectionStatus.pending,
    )
    db.add(connection)
    db.commit()
    db.refresh(connection)

    state = _new_state(db, user.id, "gocardless", connection.id)
    try:
        link = await gc.create_link(settings, institution_id, reference=state)
    except ProviderError as exc:
        db.delete(connection)
        db.commit()
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc

    cipher = CredentialCipher()
    connection.external_id = link["requisition_id"]
    connection.credentials = cipher.encrypt(
        {"requisition_id": link["requisition_id"], "agreement_id": link["agreement_id"]}
    )
    connection.consent_expires_at = datetime.fromisoformat(link["consent_expires_at"])
    db.add(connection)
    db.commit()

    return StartLinkResponse(
        connection_id=connection.id,
        authorisation_url=link["link"],
        provider="gocardless",
        expires_in_seconds=STATE_TTL_MINUTES * 60,
    )


@router.get("/gocardless/callback", response_class=HTMLResponse)
async def gocardless_callback(
    ref: str | None = Query(default=None),
    error: str | None = Query(default=None),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> HTMLResponse:
    if error:
        return _callback_page("Authorisation cancelled", f"Revolut reported: {error}", ok=False)
    if not ref:
        return _callback_page(
            "Missing reference", "The callback did not include the reference we sent.", ok=False
        )

    try:
        record = _consume_state(db, ref, "gocardless")
    except HTTPException as exc:
        return _callback_page("Could not verify the link", exc.detail, ok=False)

    connection = db.get(Connection, record.connection_id) if record.connection_id else None
    if connection is None:
        return _callback_page("Connection not found", "It may have been deleted.", ok=False)

    connection.status = ConnectionStatus.active
    connection.status_detail = None
    db.add(connection)
    db.commit()

    result = await sync_connection(db, connection, full=True, settings=settings)
    if result.errors:
        return _callback_page(
            "Connected, but the first sync failed",
            "; ".join(result.errors),
            ok=False,
        )
    return _callback_page(
        "Revolut connected",
        f"Imported {result.transactions_added} transactions across "
        f"{result.accounts_synced} account(s).",
        ok=True,
    )


# --------------------------------------------------------------------------
# Revolut Business
# --------------------------------------------------------------------------
@router.post("/revolut-business/start", response_model=StartLinkResponse)
def revolut_business_start(
    payload: StartLinkRequest,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> StartLinkResponse:
    connection = Connection(
        user_id=user.id,
        provider="revolut_business",
        label=payload.label or "Revolut Business",
        institution_id="revolut_business",
        status=ConnectionStatus.pending,
    )
    db.add(connection)
    db.commit()
    db.refresh(connection)

    state = _new_state(db, user.id, "revolut_business", connection.id)
    try:
        url = rb.build_authorize_url(settings, state)
    except ProviderError as exc:
        db.delete(connection)
        db.commit()
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc

    return StartLinkResponse(
        connection_id=connection.id,
        authorisation_url=url,
        provider="revolut_business",
        expires_in_seconds=STATE_TTL_MINUTES * 60,
    )


@router.get("/revolut-business/callback", response_class=HTMLResponse)
async def revolut_business_callback(
    code: str | None = Query(default=None),
    state: str | None = Query(default=None),
    error: str | None = Query(default=None),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> HTMLResponse:
    if error:
        return _callback_page("Authorisation cancelled", f"Revolut reported: {error}", ok=False)
    if not code or not state:
        return _callback_page("Missing code", "Revolut did not return an authorisation code.", ok=False)

    try:
        record = _consume_state(db, state, "revolut_business")
    except HTTPException as exc:
        return _callback_page("Could not verify the link", exc.detail, ok=False)

    connection = db.get(Connection, record.connection_id) if record.connection_id else None
    if connection is None:
        return _callback_page("Connection not found", "It may have been deleted.", ok=False)

    try:
        tokens = await rb.exchange_code(settings, code)
    except ProviderError as exc:
        connection.status = ConnectionStatus.error
        connection.status_detail = str(exc)
        db.add(connection)
        db.commit()
        return _callback_page("Token exchange failed", str(exc), ok=False)

    connection.credentials = CredentialCipher().encrypt(tokens)
    connection.status = ConnectionStatus.active
    connection.status_detail = None
    connection.consent_expires_at = datetime.now(timezone.utc) + timedelta(days=90)
    db.add(connection)
    db.commit()

    result = await sync_connection(db, connection, full=True, settings=settings)
    if result.errors:
        return _callback_page("Connected, but the first sync failed", "; ".join(result.errors), ok=False)
    return _callback_page(
        "Revolut Business connected",
        f"Imported {result.transactions_added} transactions across "
        f"{result.accounts_synced} account(s).",
        ok=True,
    )


# --------------------------------------------------------------------------
# Sync + teardown
# --------------------------------------------------------------------------
@router.post("/{connection_id}/sync", response_model=SyncResponse)
async def sync_one(
    connection_id: int,
    full: bool = Query(default=False),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> SyncResponse:
    connection = db.get(Connection, connection_id)
    if connection is None or connection.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Connection not found")
    result = await sync_connection(db, connection, full=full)
    return SyncResponse(**result.as_dict())


@router.post("/sync-all", response_model=SyncResponse)
async def sync_everything(
    full: bool = Query(default=False),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> SyncResponse:
    result = await sync_all(db, user.id, full=full)
    return SyncResponse(**result.as_dict())


@router.delete("/{connection_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
async def disconnect(
    connection_id: int,
    keep_data: bool = Query(
        default=True,
        description="Keep already-imported accounts and transactions (they become read-only history).",
    ),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> None:
    connection = db.get(Connection, connection_id)
    if connection is None or connection.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Connection not found")

    # Best effort: revoke consent upstream so the aggregator stops holding it.
    if connection.provider == "gocardless" and connection.external_id:
        try:
            await gc.delete_requisition(settings, connection.external_id)
        except ProviderError:
            pass

    if keep_data:
        # Detach first so the cascade doesn't take the history with it.
        for account in connection.accounts:
            account.connection_id = None
            account.is_active = False
            db.add(account)
        db.flush()

    db.delete(connection)
    db.commit()
