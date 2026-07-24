"""Shared FastAPI dependencies."""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import Cookie, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import Settings, get_settings
from .db import get_db
from .models import User, UserSession
from .security import hash_session_token

SESSION_COOKIE = "fm_session"


def current_user(
    fm_session: str | None = Cookie(default=None, alias=SESSION_COOKIE),
    db: Session = Depends(get_db),
) -> User:
    if not fm_session:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not signed in")

    session = db.scalar(
        select(UserSession).where(UserSession.token_hash == hash_session_token(fm_session))
    )
    if session is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session not found")

    expires_at = session.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    if expires_at < datetime.now(timezone.utc):
        db.delete(session)
        db.commit()
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session expired")

    user = db.get(User, session.user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User no longer exists")
    return user


def settings_dep() -> Settings:
    return get_settings()
