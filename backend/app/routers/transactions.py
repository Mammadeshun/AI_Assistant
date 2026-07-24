"""Transaction browsing and editing."""
from __future__ import annotations

import csv
import io
from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import StreamingResponse
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_user
from ..models import Account, Category, Transaction, User
from ..providers.base import from_minor
from ..schemas import (
    BulkCategorise,
    ManualTransactionCreate,
    TransactionOut,
    TransactionPage,
    TransactionUpdate,
)
from ..services.categorise import Categoriser, recategorise_all

router = APIRouter(prefix="/api/transactions", tags=["transactions"])


def _filtered(
    user_id: int,
    account_id: int | None,
    category_id: int | None,
    start: date | None,
    end: date | None,
    search: str | None,
    include_transfers: bool,
    direction: str | None,
    min_amount_minor: int | None,
    uncategorised_only: bool,
):
    query = select(Transaction).where(Transaction.user_id == user_id)
    if account_id:
        query = query.where(Transaction.account_id == account_id)
    if category_id:
        query = query.where(Transaction.category_id == category_id)
    if start:
        query = query.where(Transaction.booked_at >= start)
    if end:
        query = query.where(Transaction.booked_at <= end)
    if not include_transfers:
        query = query.where(Transaction.is_transfer.is_(False))
    if direction == "in":
        query = query.where(Transaction.amount_minor > 0)
    elif direction == "out":
        query = query.where(Transaction.amount_minor < 0)
    if min_amount_minor:
        query = query.where(func.abs(Transaction.amount_minor) >= min_amount_minor)
    if uncategorised_only:
        query = query.where(Transaction.category_id.is_(None))
    if search:
        needle = f"%{search.lower()}%"
        query = query.where(
            or_(
                func.lower(Transaction.description).like(needle),
                func.lower(func.coalesce(Transaction.merchant, "")).like(needle),
                func.lower(func.coalesce(Transaction.counterparty, "")).like(needle),
                func.lower(func.coalesce(Transaction.reference, "")).like(needle),
            )
        )
    return query


@router.get("", response_model=TransactionPage)
def list_transactions(
    account_id: int | None = None,
    category_id: int | None = None,
    start: date | None = None,
    end: date | None = None,
    search: str | None = None,
    include_transfers: bool = True,
    direction: str | None = Query(default=None, pattern="^(in|out)$"),
    min_amount_minor: int | None = None,
    uncategorised_only: bool = False,
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> TransactionPage:
    query = _filtered(
        user.id, account_id, category_id, start, end, search,
        include_transfers, direction, min_amount_minor, uncategorised_only,
    )
    total = db.scalar(select(func.count()).select_from(query.subquery())) or 0
    items = db.scalars(
        query.order_by(Transaction.booked_at.desc(), Transaction.id.desc())
        .limit(limit)
        .offset(offset)
    ).all()
    return TransactionPage(items=list(items), total=total, limit=limit, offset=offset)


@router.get("/export")
def export_transactions(
    start: date | None = None,
    end: date | None = None,
    account_id: int | None = None,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> StreamingResponse:
    query = _filtered(user.id, account_id, None, start, end, None, True, None, None, False)
    rows = db.scalars(query.order_by(Transaction.booked_at.asc())).all()

    categories = {
        category.id: category.name
        for category in db.scalars(select(Category).where(Category.user_id == user.id)).all()
    }
    accounts = {
        account.id: account.name
        for account in db.scalars(select(Account).where(Account.user_id == user.id)).all()
    }

    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(
        ["Date", "Account", "Description", "Merchant", "Category", "Amount", "Currency", "State", "Transfer"]
    )
    for txn in rows:
        writer.writerow(
            [
                txn.booked_at.isoformat(),
                accounts.get(txn.account_id, ""),
                txn.description,
                txn.merchant or "",
                categories.get(txn.category_id, "Uncategorised"),
                f"{from_minor(txn.amount_minor, txn.currency):.2f}",
                txn.currency,
                txn.state,
                "yes" if txn.is_transfer else "no",
            ]
        )
    buffer.seek(0)
    filename = f"transactions-{date.today().isoformat()}.csv"
    return StreamingResponse(
        iter([buffer.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.post("", response_model=TransactionOut, status_code=status.HTTP_201_CREATED)
def create_manual_transaction(
    payload: ManualTransactionCreate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Transaction:
    account = db.get(Account, payload.account_id)
    if account is None or account.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")

    category_id = payload.category_id
    locked = category_id is not None
    if category_id is None:
        result = Categoriser(db, user.id).categorise(
            payload.description, payload.merchant, None, None, None, payload.amount_minor
        )
        category_id = result.category_id

    txn = Transaction(
        user_id=user.id,
        account_id=account.id,
        external_id=None,
        dedupe_key=f"manual:{date.today().isoformat()}:{payload.description[:20]}:{payload.amount_minor}",
        booked_at=payload.booked_at,
        value_date=payload.booked_at,
        amount_minor=payload.amount_minor,
        currency=account.currency,
        description=payload.description,
        merchant=payload.merchant,
        notes=payload.notes,
        category_id=category_id,
        category_locked=locked,
        is_transfer=payload.is_transfer,
        state="completed",
    )
    db.add(txn)
    db.commit()
    db.refresh(txn)
    return txn


@router.patch("/{transaction_id}", response_model=TransactionOut)
def update_transaction(
    transaction_id: int,
    payload: TransactionUpdate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Transaction:
    txn = db.get(Transaction, transaction_id)
    if txn is None or txn.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Transaction not found")

    data = payload.model_dump(exclude_unset=True)
    if "category_id" in data:
        _assert_category(db, user.id, data["category_id"])
        txn.category_locked = True  # a hand-set category survives future re-categorisation
    for field, value in data.items():
        setattr(txn, field, value)

    db.add(txn)
    db.commit()
    db.refresh(txn)
    return txn


@router.post("/bulk-categorise", response_model=dict)
def bulk_categorise(
    payload: BulkCategorise,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict:
    _assert_category(db, user.id, payload.category_id)
    rows = db.scalars(
        select(Transaction).where(
            Transaction.user_id == user.id, Transaction.id.in_(payload.transaction_ids)
        )
    ).all()
    for txn in rows:
        txn.category_id = payload.category_id
        txn.category_locked = True
        db.add(txn)
    db.commit()
    return {"updated": len(rows)}


@router.post("/recategorise", response_model=dict)
def recategorise(
    include_locked: bool = Query(
        default=False, description="Also overwrite categories you set by hand."
    ),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict:
    changed = recategorise_all(db, user.id, include_locked=include_locked)
    return {"updated": changed}


@router.delete("/{transaction_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def delete_transaction(
    transaction_id: int,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> None:
    txn = db.get(Transaction, transaction_id)
    if txn is None or txn.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Transaction not found")
    db.delete(txn)
    db.commit()


def _assert_category(db: Session, user_id: int, category_id: int | None) -> None:
    if category_id is None:
        return
    category = db.get(Category, category_id)
    if category is None or category.user_id != user_id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category")
