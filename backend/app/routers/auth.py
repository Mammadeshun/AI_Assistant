"""Sign-up / sign-in for the single household using this instance."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import get_db
from ..deps import SESSION_COOKIE, current_user, settings_dep
from ..models import User, UserSession
from ..schemas import LoginRequest, RegisterRequest, UserOut
from ..security import hash_password, hash_session_token, new_session_token, verify_password
from ..services.categorise import ensure_default_categories

router = APIRouter(prefix="/api/auth", tags=["auth"])


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
def auth_status(db: Session = Depends(get_db)) -> dict:
    """Lets the UI decide between the sign-up and sign-in screens."""
    return {"registered": db.scalar(select(func.count(User.id))) > 0}


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
    user = db.scalar(select(User).where(User.email == payload.email.lower()))
    if user is None or not verify_password(payload.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect email or password")

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
