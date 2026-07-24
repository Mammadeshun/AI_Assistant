"""Read-only analytics endpoints backing the dashboard."""
from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_user
from ..models import User
from ..schemas import SyncResponse  # noqa: F401  (kept for openapi consistency)
from ..services import analytics as svc

router = APIRouter(prefix="/api/analytics", tags=["analytics"])


def _resolve_range(
    start: date | None, end: date | None, months: int | None
) -> tuple[date, date]:
    today = date.today()
    if start and end:
        return start, end
    if months:
        return today - timedelta(days=30 * months), today
    return svc.month_bounds(today)


@router.get("/summary")
def summary(
    start: date | None = None,
    end: date | None = None,
    months: int | None = Query(default=None, ge=1, le=60),
    currency: str | None = None,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict:
    period_start, period_end = _resolve_range(start, end, months)
    return svc.period_summary(
        db, user.id, period_start, period_end, (currency or user.base_currency).upper()
    )


@router.get("/by-category")
def by_category(
    start: date | None = None,
    end: date | None = None,
    months: int | None = Query(default=None, ge=1, le=60),
    currency: str | None = None,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[dict]:
    period_start, period_end = _resolve_range(start, end, months)
    return svc.spend_by_category(
        db, user.id, period_start, period_end, (currency or user.base_currency).upper()
    )


@router.get("/cash-flow")
def cash_flow(
    months: int = Query(default=12, ge=2, le=36),
    currency: str | None = None,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[dict]:
    return svc.cash_flow(db, user.id, months, (currency or user.base_currency).upper())


@router.get("/net-worth")
def net_worth(user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    return svc.net_worth(db, user.id)


@router.get("/top-merchants")
def top_merchants(
    start: date | None = None,
    end: date | None = None,
    months: int | None = Query(default=None, ge=1, le=60),
    limit: int = Query(default=10, ge=1, le=50),
    currency: str | None = None,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[dict]:
    period_start, period_end = _resolve_range(start, end, months)
    return svc.top_merchants(
        db, user.id, period_start, period_end, (currency or user.base_currency).upper(), limit
    )


@router.get("/budgets")
def budgets(user: User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict]:
    return svc.budget_progress(db, user.id)


@router.get("/recurring")
def recurring(
    lookback_days: int = Query(default=400, ge=60, le=1200),
    currency: str | None = None,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[dict]:
    return svc.detect_recurring(
        db, user.id, lookback_days, (currency or user.base_currency).upper()
    )


@router.get("/insights")
def insights(user: User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict]:
    return svc.insights(db, user.id, user.base_currency)


@router.get("/dashboard")
def dashboard(
    user: User = Depends(current_user), db: Session = Depends(get_db)
) -> dict:
    """Everything the landing screen needs, in one round trip."""
    currency = user.base_currency
    today = date.today()
    start, end = svc.month_bounds(today)
    return {
        "currency": currency,
        "month": {"start": start.isoformat(), "end": end.isoformat()},
        "summary": svc.period_summary(db, user.id, start, end, currency),
        "net_worth": svc.net_worth(db, user.id),
        "by_category": svc.spend_by_category(db, user.id, start, end, currency)[:10],
        "cash_flow": svc.cash_flow(db, user.id, 12, currency),
        "top_merchants": svc.top_merchants(db, user.id, start, end, currency, 8),
        "budgets": svc.budget_progress(db, user.id),
        "recurring": svc.detect_recurring(db, user.id, currency=currency)[:12],
        "insights": svc.insights(db, user.id, currency),
    }
