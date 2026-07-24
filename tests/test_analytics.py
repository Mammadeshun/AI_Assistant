"""Analytics maths, including the multi-currency guard rails."""
from __future__ import annotations

from datetime import date, timedelta

import pytest

from backend.app.models import Account, Budget, Transaction
from backend.app.services import analytics as svc
from backend.app.services.categorise import ensure_default_categories


@pytest.fixture
def account(db, user_id):
    record = Account(
        user_id=user_id, external_id="a1", name="Revolut GBP", currency="GBP", balance_minor=100_000
    )
    db.add(record)
    db.commit()
    db.refresh(record)
    return record


@pytest.fixture
def categories(db, user_id):
    result = ensure_default_categories(db, user_id)
    db.commit()
    return result


def add_txn(db, account, day, amount_minor, description="Thing", currency=None,
            category_id=None, is_transfer=False):
    txn = Transaction(
        user_id=account.user_id,
        account_id=account.id,
        dedupe_key=f"{day}-{amount_minor}-{description}-{id(description)}",
        booked_at=day,
        amount_minor=amount_minor,
        currency=currency or account.currency,
        description=description,
        merchant=description,
        category_id=category_id,
        is_transfer=is_transfer,
    )
    db.add(txn)
    db.commit()
    return txn


def test_period_summary_splits_income_and_spend(db, user_id, account):
    start, end = svc.month_bounds(date.today())
    add_txn(db, account, start, 200_000, "Salary")
    add_txn(db, account, start, -50_000, "Rent")
    add_txn(db, account, start, -1_000, "Coffee")

    summary = svc.period_summary(db, user_id, start, end, "GBP")
    assert summary["income_minor"] == 200_000
    assert summary["expense_minor"] == 51_000
    assert summary["net_minor"] == 149_000
    assert summary["transaction_count"] == 3


def test_transfers_are_excluded_from_spending(db, user_id, account):
    start, end = svc.month_bounds(date.today())
    add_txn(db, account, start, -100_000, "To savings", is_transfer=True)
    add_txn(db, account, start, -2_000, "Lunch")

    summary = svc.period_summary(db, user_id, start, end, "GBP")
    assert summary["expense_minor"] == 2_000


def test_other_currencies_are_reported_separately_not_summed(db, user_id, account):
    start, end = svc.month_bounds(date.today())
    add_txn(db, account, start, -10_000, "GBP spend")
    add_txn(db, account, start, -5_000, "EUR spend", currency="EUR")

    summary = svc.period_summary(db, user_id, start, end, "GBP")
    assert summary["expense_minor"] == 10_000  # EUR is not folded in
    assert summary["other_currencies"] == [
        {"currency": "EUR", "income_minor": 0, "expense_minor": 5_000, "net_minor": -5_000}
    ]


def test_spend_by_category_shares_sum_to_one(db, user_id, account, categories):
    start, end = svc.month_bounds(date.today())
    add_txn(db, account, start, -7_500, "Tesco", category_id=categories["Groceries"].id)
    add_txn(db, account, start, -2_500, "Uber", category_id=categories["Transport"].id)

    rows = svc.spend_by_category(db, user_id, start, end, "GBP")
    assert [row["name"] for row in rows] == ["Groceries", "Transport"]
    assert rows[0]["share"] == 0.75
    assert sum(row["share"] for row in rows) == pytest.approx(1.0)


def test_cash_flow_returns_a_bucket_per_month_including_empty_ones(db, user_id, account):
    add_txn(db, account, date.today(), -1_000, "Now")
    rows = svc.cash_flow(db, user_id, 6, "GBP")
    assert len(rows) == 6
    assert rows[-1]["expense_minor"] == 1_000
    assert rows[0]["expense_minor"] == 0


def test_net_worth_groups_by_currency(db, user_id, account):
    db.add(Account(user_id=user_id, external_id="a2", name="EUR", currency="EUR", balance_minor=25_000))
    db.commit()

    result = svc.net_worth(db, user_id)
    assert result["account_count"] == 2
    assert result["balances"] == [
        {"currency": "EUR", "amount_minor": 25_000},
        {"currency": "GBP", "amount_minor": 100_000},
    ]


def test_net_worth_respects_the_exclude_flag(db, user_id, account):
    account.include_in_net_worth = False
    db.commit()
    assert svc.net_worth(db, user_id)["account_count"] == 0


def test_budget_progress(db, user_id, account, categories):
    start, _ = svc.month_bounds(date.today())
    groceries = categories["Groceries"]
    db.add(Budget(user_id=user_id, category_id=groceries.id, amount_minor=40_000, currency="GBP"))
    db.commit()
    add_txn(db, account, start, -30_000, "Tesco", category_id=groceries.id)

    rows = svc.budget_progress(db, user_id)
    assert len(rows) == 1
    assert rows[0]["spent_minor"] == 30_000
    assert rows[0]["remaining_minor"] == 10_000
    assert rows[0]["used_share"] == 0.75


def test_recurring_detection_finds_a_steady_subscription(db, user_id, account):
    for months_ago in range(6):
        day = date.today() - timedelta(days=30 * months_ago)
        add_txn(db, account, day, -1_099, "Netflix")

    recurring = svc.detect_recurring(db, user_id)
    names = [item["name"] for item in recurring]
    assert "Netflix" in names

    netflix = next(item for item in recurring if item["name"] == "Netflix")
    assert netflix["cadence"] == "monthly"
    assert netflix["typical_amount_minor"] == 1_099
    assert netflix["annualised_minor"] == pytest.approx(13_371, abs=200)


def test_irregular_payments_are_not_flagged_as_recurring(db, user_id, account):
    for offset, amount in [(0, -500), (3, -9_000), (40, -120), (95, -3_300)]:
        add_txn(db, account, date.today() - timedelta(days=offset), amount, "Random Shop")

    assert [item["name"] for item in svc.detect_recurring(db, user_id)] == []


def test_two_payments_are_not_enough_for_recurring(db, user_id, account):
    add_txn(db, account, date.today(), -1_000, "Gym")
    add_txn(db, account, date.today() - timedelta(days=30), -1_000, "Gym")
    assert svc.detect_recurring(db, user_id) == []


def test_insights_flag_an_overspent_budget(db, user_id, account, categories):
    start, _ = svc.month_bounds(date.today())
    groceries = categories["Groceries"]
    db.add(Budget(user_id=user_id, category_id=groceries.id, amount_minor=10_000, currency="GBP"))
    db.commit()
    add_txn(db, account, start, -15_000, "Tesco", category_id=groceries.id)

    titles = [item["title"] for item in svc.insights(db, user_id, "GBP")]
    assert any("budget is blown" in title for title in titles)


def test_top_merchants_ordered_by_spend(db, user_id, account):
    start, end = svc.month_bounds(date.today())
    add_txn(db, account, start, -5_000, "Amazon")
    add_txn(db, account, start, -12_000, "Ikea")

    rows = svc.top_merchants(db, user_id, start, end, "GBP")
    assert [row["name"] for row in rows] == ["Ikea", "Amazon"]
    assert rows[0]["amount_minor"] == 12_000
