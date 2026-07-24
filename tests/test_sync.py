"""Sync upserts: idempotency, pending settlement, balance handling."""
from __future__ import annotations

from datetime import date

import pytest
from sqlalchemy import select

from backend.app.models import Account, Connection, ConnectionStatus, Transaction
from backend.app.providers.base import NormalisedAccount, NormalisedTransaction
from backend.app.services.categorise import Categoriser, ensure_default_categories
from backend.app.services.sync import _upsert_account, _upsert_transactions


@pytest.fixture
def connection(db, user_id):
    record = Connection(
        user_id=user_id, provider="gocardless", label="Revolut", status=ConnectionStatus.active
    )
    db.add(record)
    db.commit()
    db.refresh(record)
    return record


@pytest.fixture
def account(db, connection):
    return _upsert_account(
        db,
        connection,
        NormalisedAccount(
            external_id="acct-1", name="Revolut GBP", currency="GBP", balance_minor=100_000
        ),
    )


@pytest.fixture
def categoriser(db, user_id):
    ensure_default_categories(db, user_id)
    db.commit()
    return Categoriser(db, user_id)


def _txn(**overrides):
    base = dict(
        external_id="t1",
        booked_at=date(2025, 3, 1),
        amount_minor=-4250,
        currency="GBP",
        description="TESCO STORES",
    )
    return NormalisedTransaction(**{**base, **overrides})


def test_account_upsert_is_idempotent(db, connection, account):
    updated = _upsert_account(
        db,
        connection,
        NormalisedAccount(
            external_id="acct-1", name="Renamed", currency="GBP", balance_minor=95_000
        ),
    )
    assert updated.id == account.id
    assert updated.name == "Renamed"
    assert updated.balance_minor == 95_000
    assert db.scalar(select(Account).where(Account.connection_id == connection.id)) is not None
    assert len(db.scalars(select(Account)).all()) == 1


def test_transactions_are_not_duplicated_on_resync(db, account, categoriser):
    added, updated = _upsert_transactions(db, account, [_txn()], categoriser)
    assert (added, updated) == (1, 0)

    added, updated = _upsert_transactions(db, account, [_txn()], categoriser)
    assert (added, updated) == (0, 0)
    assert len(db.scalars(select(Transaction)).all()) == 1


def test_duplicates_within_one_payload_are_collapsed(db, account, categoriser):
    added, _ = _upsert_transactions(db, account, [_txn(), _txn()], categoriser)
    assert added == 1


def test_pending_transaction_settles_in_place(db, account, categoriser):
    _upsert_transactions(db, account, [_txn(state="pending")], categoriser)
    added, updated = _upsert_transactions(db, account, [_txn(state="completed")], categoriser)

    assert (added, updated) == (0, 1)
    stored = db.scalars(select(Transaction)).all()
    assert len(stored) == 1
    assert stored[0].state == "completed"


def test_new_transactions_get_categorised(db, account, categoriser):
    _upsert_transactions(db, account, [_txn()], categoriser)
    stored = db.scalar(select(Transaction))
    assert stored.category_id == categoriser.categories["Groceries"].id


def test_transactions_without_external_id_dedupe_on_content(db, account, categoriser):
    rows = [_txn(external_id=None), _txn(external_id=None, description="tesco   stores")]
    added, _ = _upsert_transactions(db, account, rows, categoriser)
    assert added == 1


def test_different_accounts_keep_separate_transactions(db, connection, account, categoriser):
    other = _upsert_account(
        db,
        connection,
        NormalisedAccount(external_id="acct-2", name="EUR", currency="EUR", balance_minor=0),
    )
    _upsert_transactions(db, account, [_txn()], categoriser)
    _upsert_transactions(db, other, [_txn()], categoriser)
    assert len(db.scalars(select(Transaction)).all()) == 2
