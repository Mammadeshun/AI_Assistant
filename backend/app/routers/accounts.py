"""Accounts: list, tweak, create manual ones, import CSV statements."""
from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_user
from ..models import Account, Transaction, User, utcnow
from ..providers.base import ProviderError
from ..providers.csv_import import parse_csv
from ..providers.pdf_import import count_undated, parse_pdf
from ..schemas import AccountOut, AccountUpdate, ImportResponse, ManualAccountCreate
from ..services.sync import import_normalised

router = APIRouter(prefix="/api/accounts", tags=["accounts"])

MAX_UPLOAD_BYTES = 10 * 1024 * 1024


@router.get("", response_model=list[AccountOut])
def list_accounts(
    include_inactive: bool = False,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[Account]:
    query = select(Account).where(Account.user_id == user.id)
    if not include_inactive:
        query = query.where(Account.is_active.is_(True))
    return list(db.scalars(query.order_by(Account.name)).all())


@router.post("", response_model=AccountOut, status_code=status.HTTP_201_CREATED)
def create_manual_account(
    payload: ManualAccountCreate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Account:
    account = Account(
        user_id=user.id,
        connection_id=None,
        external_id=f"manual:{payload.name.lower().replace(' ', '-')}",
        name=payload.name,
        currency=payload.currency,
        account_type=payload.account_type or "manual",
        balance_minor=payload.opening_balance_minor,
    )
    db.add(account)
    db.commit()
    db.refresh(account)
    return account


@router.patch("/{account_id}", response_model=AccountOut)
def update_account(
    account_id: int,
    payload: AccountUpdate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Account:
    account = db.get(Account, account_id)
    if account is None or account.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(account, field, value)
    db.add(account)
    db.commit()
    db.refresh(account)
    return account


@router.post("/{account_id}/import", response_model=ImportResponse)
async def import_statement(
    account_id: int,
    file: UploadFile = File(...),
    recalculate_balance: bool = Form(default=False),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> ImportResponse:
    """Import a CSV or PDF statement into an existing account.

    Revolut hands out a PDF unless you go looking for the CSV, so both are
    accepted and the format is detected from the bytes rather than the filename.
    Re-importing an overlapping file is safe: rows carry a stable identity, so
    what is already there is updated rather than duplicated.
    """
    account = db.get(Account, account_id)
    if account is None or account.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")

    content = await file.read()
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "File is larger than 10 MB.")

    is_pdf = content[:5] == b"%PDF-"
    try:
        if is_pdf:
            transactions = parse_pdf(content, default_currency=account.currency)
        else:
            transactions = parse_csv(content, default_currency=account.currency)
    except ProviderError as exc:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(exc)) from exc

    added, updated = import_normalised(db, account, transactions)

    if recalculate_balance:
        total = db.scalar(
            select(func.coalesce(func.sum(Transaction.amount_minor), 0)).where(
                Transaction.account_id == account.id,
                Transaction.currency == account.currency,
            )
        )
        account.balance_minor = int(total or 0)
        account.last_synced_at = utcnow()
        db.add(account)
        db.commit()

    return ImportResponse(
        account_id=account.id,
        parsed=len(transactions),
        added=added,
        updated=updated,
        format="pdf" if is_pdf else "csv",
        dates_estimated=count_undated(transactions) if is_pdf else 0,
    )


@router.delete("/{account_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def delete_account(
    account_id: int,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> None:
    """Deletes the account and every transaction on it."""
    account = db.get(Account, account_id)
    if account is None or account.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
    db.delete(account)
    db.commit()
