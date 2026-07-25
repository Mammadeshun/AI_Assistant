"""Sync orchestration: pull from a provider, upsert into the database."""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..models import (
    Account,
    Connection,
    ConnectionStatus,
    SyncLog,
    Transaction,
    User,
    utcnow,
)
from ..providers.base import (
    BankProvider,
    ConsentExpired,
    NormalisedAccount,
    NormalisedTransaction,
    ProviderError,
)
from ..providers.gocardless import GoCardlessProvider
from ..providers.revolut_business import RevolutBusinessProvider
from ..security import CredentialCipher
from .categorise import Categoriser

logger = logging.getLogger(__name__)

PROVIDER_REGISTRY: dict[str, type] = {
    RevolutBusinessProvider.key: RevolutBusinessProvider,
    GoCardlessProvider.key: GoCardlessProvider,
}


class SyncResult:
    def __init__(self) -> None:
        self.accounts_synced = 0
        self.transactions_added = 0
        self.transactions_updated = 0
        self.errors: list[str] = []

    def as_dict(self) -> dict[str, Any]:
        return {
            "accounts_synced": self.accounts_synced,
            "transactions_added": self.transactions_added,
            "transactions_updated": self.transactions_updated,
            "errors": self.errors,
            "ok": not self.errors,
        }


def build_provider(db: Session, connection: Connection, settings: Settings | None = None) -> BankProvider:
    settings = settings or get_settings()
    provider_class = PROVIDER_REGISTRY.get(connection.provider)
    if provider_class is None:
        raise ProviderError(f"Unknown provider '{connection.provider}'.")

    cipher = CredentialCipher()
    credentials = cipher.decrypt(connection.credentials)

    def persist(updated: dict[str, Any]) -> None:
        connection.credentials = cipher.encrypt(updated)
        db.add(connection)
        db.commit()

    return provider_class(settings, credentials, persist)


async def sync_connection(
    db: Session, connection: Connection, full: bool = False, settings: Settings | None = None
) -> SyncResult:
    settings = settings or get_settings()
    result = SyncResult()
    log = SyncLog(user_id=connection.user_id, connection_id=connection.id, status="running")
    db.add(log)
    db.commit()

    try:
        provider = build_provider(db, connection, settings)
        remote_accounts = await provider.fetch_accounts()
        categoriser = Categoriser(db, connection.user_id)

        for remote in remote_accounts:
            account = _upsert_account(db, connection, remote)
            result.accounts_synced += 1

            since = _since_for(account, connection, full, settings)
            transactions = await provider.fetch_transactions(remote.external_id, since)
            added, updated = _upsert_transactions(db, account, transactions, categoriser)
            result.transactions_added += added
            result.transactions_updated += updated

            account.last_synced_at = utcnow()
            db.add(account)

        connection.status = ConnectionStatus.active
        connection.status_detail = None
        connection.last_synced_at = utcnow()
        db.add(connection)

        log.status = "success"
    except ConsentExpired as exc:
        connection.status = ConnectionStatus.expired
        connection.status_detail = str(exc)
        db.add(connection)
        log.status = "expired"
        log.message = str(exc)
        result.errors.append(str(exc))
    except ProviderError as exc:
        connection.status = ConnectionStatus.error
        connection.status_detail = str(exc)
        db.add(connection)
        log.status = "error"
        log.message = str(exc)
        result.errors.append(str(exc))
    except Exception as exc:  # noqa: BLE001 - last resort, recorded on the sync log
        logger.exception("Unexpected sync failure for connection %s", connection.id)
        connection.status = ConnectionStatus.error
        connection.status_detail = f"Unexpected error: {exc}"
        db.add(connection)
        log.status = "error"
        log.message = str(exc)
        result.errors.append(str(exc))

    log.finished_at = utcnow()
    log.accounts_synced = result.accounts_synced
    log.transactions_added = result.transactions_added
    log.transactions_updated = result.transactions_updated
    db.add(log)
    db.commit()
    return result


async def sync_all(db: Session, user_id: int, full: bool = False) -> SyncResult:
    combined = SyncResult()
    connections = db.scalars(
        select(Connection).where(
            Connection.user_id == user_id,
            Connection.status.in_([ConnectionStatus.active, ConnectionStatus.error]),
        )
    ).all()
    for connection in connections:
        result = await sync_connection(db, connection, full=full)
        combined.accounts_synced += result.accounts_synced
        combined.transactions_added += result.transactions_added
        combined.transactions_updated += result.transactions_updated
        combined.errors.extend(f"{connection.label}: {error}" for error in result.errors)
    return combined


# --------------------------------------------------------------------------
# Upserts
# --------------------------------------------------------------------------
def _upsert_account(db: Session, connection: Connection, remote: NormalisedAccount) -> Account:
    account = db.scalar(
        select(Account).where(
            Account.connection_id == connection.id, Account.external_id == remote.external_id
        )
    )
    if account is None:
        account = Account(
            user_id=connection.user_id,
            connection_id=connection.id,
            external_id=remote.external_id,
        )
        db.add(account)

    account.name = remote.name
    account.currency = remote.currency
    account.balance_minor = remote.balance_minor
    account.available_minor = remote.available_minor
    account.account_type = remote.account_type
    account.iban_last4 = remote.iban_last4
    account.is_active = True
    db.flush()
    return account


def _upsert_transactions(
    db: Session,
    account: Account,
    transactions: list[NormalisedTransaction],
    categoriser: Categoriser,
) -> tuple[int, int]:
    if not transactions:
        return 0, 0

    keys = [txn.dedupe_key() for txn in transactions]
    existing = {
        row.dedupe_key: row
        for row in db.scalars(
            select(Transaction).where(
                Transaction.account_id == account.id, Transaction.dedupe_key.in_(keys)
            )
        ).all()
    }

    added = updated = 0
    staged: set[str] = set()  # keys added earlier in this same payload
    for remote, key in zip(transactions, keys):
        if key in staged:
            continue
        record = existing.get(key)
        if record is None:
            result = categoriser.categorise(
                remote.description,
                remote.merchant,
                remote.counterparty,
                remote.reference,
                remote.provider_category,
                remote.amount_minor,
            )
            db.add(
                Transaction(
                    user_id=account.user_id,
                    account_id=account.id,
                    external_id=remote.external_id,
                    dedupe_key=key,
                    booked_at=remote.booked_at,
                    value_date=remote.value_date,
                    amount_minor=remote.amount_minor,
                    currency=remote.currency,
                    description=remote.description[:400],
                    merchant=(result.merchant or remote.merchant or None),
                    counterparty=remote.counterparty,
                    reference=remote.reference,
                    provider_category=remote.provider_category,
                    state=remote.state,
                    category_id=result.category_id,
                    is_transfer=result.is_transfer,
                )
            )
            staged.add(key)
            added += 1
        elif record.state != remote.state and remote.state == "completed":
            # A pending transaction settled: refresh the mutable bits only.
            record.state = "completed"
            record.amount_minor = remote.amount_minor
            record.booked_at = remote.booked_at
            db.add(record)
            updated += 1

    db.commit()
    return added, updated


def _since_for(
    account: Account, connection: Connection, full: bool, settings: Settings
) -> date:
    if full or account.last_synced_at is None:
        return date.today() - timedelta(days=settings.initial_sync_days)
    last = account.last_synced_at
    if last.tzinfo is None:
        last = last.replace(tzinfo=timezone.utc)
    # Re-read a short overlap so late-posting and pending transactions land.
    return (last - timedelta(days=settings.sync_overlap_days)).date()


def import_normalised(
    db: Session, account: Account, transactions: list[NormalisedTransaction]
) -> tuple[int, int]:
    """Used by the file importers; shares the dedupe/categorise path with live syncs."""
    categoriser = Categoriser(db, account.user_id)
    added, updated = _upsert_transactions(db, account, transactions, categoriser)
    account.last_synced_at = datetime.now(timezone.utc)
    db.add(account)
    db.commit()
    adopt_single_currency(db, account.user_id)
    return added, updated


def adopt_single_currency(db: Session, user_id: int) -> str | None:
    """Point the dashboard at the currency the user's money is actually in.

    Totals are never converted, so a EUR statement under the default GBP base
    currency produces a dashboard of zeros with everything filed under "other
    currencies" — accurate, and useless. When every transaction on the books is
    in one currency there is nothing to weigh up, so the base currency follows
    it. Anyone holding two currencies has made a real choice, and it is left
    alone.
    """
    user = db.get(User, user_id)
    if user is None:
        return None

    currencies = set(
        db.scalars(
            select(Transaction.currency)
            .join(Account, Transaction.account_id == Account.id)
            .where(Account.user_id == user_id)
            .distinct()
        )
    )
    if len(currencies) != 1:
        return None

    only = currencies.pop().upper()
    if only == user.base_currency.upper():
        return None

    user.base_currency = only
    db.add(user)
    db.commit()
    return only
