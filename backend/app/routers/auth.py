"""Sign-up / sign-in for the single household using this instance."""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import get_db
from ..deps import SESSION_COOKIE, current_user, settings_dep
from ..models import User, UserSession
from ..schemas import LoginRequest, RegisterRequest, UserOut
from ..security import (
    constant_time_equals,
    hash_password,
    hash_session_token,
    new_session_token,
    verify_password,
)
from ..services.categorise import ensure_default_categories

router = APIRouter(prefix="/api/auth", tags=["auth"])

# Failed sign-in attempts, keyed by email. In memory on purpose: this is a
# single-process personal instance, and a restart clearing the counters is an
# acceptable trade for having no extra dependency. It exists to make online
# password guessing impractical, not to survive a determined attacker.
_failed_logins: dict[str, list[float]] = {}


def _seconds_locked_out(email: str, settings: Settings) -> int:
    attempts = _failed_logins.get(email, [])
    cutoff = time.time() - settings.login_lockout_seconds
    recent = [stamp for stamp in attempts if stamp > cutoff]
    _failed_logins[email] = recent
    if len(recent) < settings.login_max_attempts:
        return 0
    return int(recent[0] + settings.login_lockout_seconds - time.time()) + 1


def _record_failure(email: str) -> None:
    _failed_logins.setdefault(email, []).append(time.time())


def _clear_failures(email: str) -> None:
    _failed_logins.pop(email, None)


def _issue_session(
    db: Session, response: Response, user: User, settings: Settings, user_agent: str | None
) -> None:
    token = new_session_token()
    expires_at = datetime.now(timezone.utc) + timedelta(hours=settings.session_ttl_hours)
    db.add(
        UserSession(
            user_id=user.id,
            token_hash=hash_session_token(token),
            expires_at=expires_at,
            user_agent=(user_agent or "")[:255],
        )
    )
    db.commit()
    response.set_cookie(
        SESSION_COOKIE,
        token,
        httponly=True,
        samesite="lax",
        secure=settings.cookie_secure,
        max_age=settings.session_ttl_hours * 3600,
        path="/",
    )


@router.get("/status")
def auth_status(
    db: Session = Depends(get_db), settings: Settings = Depends(settings_dep)
) -> dict:
    """Lets the UI decide between the sign-up and sign-in screens."""
    return {
        "registered": db.scalar(select(func.count(User.id))) > 0,
        "signup_token_required": bool(settings.signup_token),
    }


@router.post("/register", response_model=UserOut, status_code=status.HTTP_201_CREATED)
def register(
    payload: RegisterRequest,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> User:
    if db.scalar(select(func.count(User.id))) > 0:
        # This is a personal instance: one account, no open registration.
        raise HTTPException(status.HTTP_403_FORBIDDEN, "An account already exists on this instance.")

    # On a public deployment the sign-up form is reachable by anyone who finds
    # the URL, and the first person through it claims the instance. The token
    # closes that window.
    if settings.signup_token:
        supplied = payload.signup_token or ""
        if not constant_time_equals(supplied, settings.signup_token):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Incorrect sign-up code.")

    user = User(
        email=payload.email.lower(),
        password_hash=hash_password(payload.password),
        display_name=payload.display_name,
        base_currency=payload.base_currency,
    )
    db.add(user)
    db.commit()
    db.refresh(user)

    ensure_default_categories(db, user.id)
    db.commit()

    _issue_session(db, response, user, settings, request.headers.get("user-agent"))
    return user


@router.post("/login", response_model=UserOut)
def login(
    payload: LoginRequest,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> User:
    email = payload.email.lower()

    locked_for = _seconds_locked_out(email, settings)
    if locked_for:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            f"Too many failed attempts. Try again in {locked_for} seconds.",
        )

    user = db.scalar(select(User).where(User.email == email))
    if user is None or not verify_password(payload.password, user.password_hash):
        _record_failure(email)
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect email or password")

    _clear_failures(email)
    _issue_session(db, response, user, settings, request.headers.get("user-agent"))
    return user


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def logout(
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
) -> None:
    token = request.cookies.get(SESSION_COOKIE)
    if token:
        session = db.scalar(
            select(UserSession).where(UserSession.token_hash == hash_session_token(token))
        )
        if session:
            db.delete(session)
            db.commit()
    response.delete_cookie(SESSION_COOKIE, path="/")


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(current_user)) -> User:
    return user
