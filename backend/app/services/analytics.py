"""Analytics over stored transactions.

No FX rates are invented here. Amounts are aggregated per currency; the
dashboard shows the base currency as the headline and lists any other
currencies separately, so a EUR pocket never silently inflates a GBP total.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from statistics import median
from typing import Any, Iterable

from dateutil.relativedelta import relativedelta
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..models import Account, Budget, Category, CategoryKind, Transaction


def month_bounds(anchor: date) -> tuple[date, date]:
    start = anchor.replace(day=1)
    end = start + relativedelta(months=1) - timedelta(days=1)
    return start, end


def _base_query(user_id: int, start: date, end: date, include_transfers: bool = False):
    query = select(Transaction).where(
        Transaction.user_id == user_id,
        Transaction.booked_at >= start,
        Transaction.booked_at <= end,
    )
    if not include_transfers:
        query = query.where(Transaction.is_transfer.is_(False))
    return query


@dataclass(slots=True)
class MoneyTotals:
    income_minor: int = 0
    expense_minor: int = 0

    @property
    def net_minor(self) -> int:
        return self.income_minor - self.expense_minor


def period_summary(
    db: Session, user_id: int, start: date, end: date, base_currency: str
) -> dict[str, Any]:
    rows = db.scalars(_base_query(user_id, start, end)).all()

    per_currency: dict[str, MoneyTotals] = defaultdict(MoneyTotals)
    for txn in rows:
        totals = per_currency[txn.currency]
        if txn.amount_minor >= 0:
            totals.income_minor += txn.amount_minor
        else:
            totals.expense_minor += -txn.amount_minor

    base = per_currency.get(base_currency, MoneyTotals())
    days_elapsed = max((min(end, date.today()) - start).days + 1, 1)
    period_days = (end - start).days + 1

    return {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "currency": base_currency,
        "income_minor": base.income_minor,
        "expense_minor": base.expense_minor,
        "net_minor": base.net_minor,
        "transaction_count": len(rows),
        "average_daily_spend_minor": round(base.expense_minor / days_elapsed) if days_elapsed else 0,
        "projected_month_spend_minor": (
            round(base.expense_minor / days_elapsed * period_days) if days_elapsed else 0
        ),
        "other_currencies": [
            {
                "currency": currency,
                "income_minor": totals.income_minor,
                "expense_minor": totals.expense_minor,
                "net_minor": totals.net_minor,
            }
            for currency, totals in sorted(per_currency.items())
            if currency != base_currency
        ],
    }


def spend_by_category(
    db: Session, user_id: int, start: date, end: date, currency: str
) -> list[dict[str, Any]]:
    rows = db.execute(
        select(
            Transaction.category_id,
            func.sum(Transaction.amount_minor),
            func.count(Transaction.id),
        )
        .where(
            Transaction.user_id == user_id,
            Transaction.booked_at >= start,
            Transaction.booked_at <= end,
            Transaction.currency == currency,
            Transaction.is_transfer.is_(False),
            Transaction.amount_minor < 0,
        )
        .group_by(Transaction.category_id)
    ).all()

    categories = {
        category.id: category
        for category in db.scalars(select(Category).where(Category.user_id == user_id)).all()
    }

    total = sum(-amount for _, amount, _ in rows) or 0
    out = []
    for category_id, amount, count in rows:
        category = categories.get(category_id)
        spend = -int(amount)
        out.append(
            {
                "category_id": category_id,
                "name": category.name if category else "Uncategorised",
                "colour": category.colour if category else "#9e9e9e",
                "icon": category.icon if category else "❓",
                "amount_minor": spend,
                "transaction_count": count,
                "share": round(spend / total, 4) if total else 0.0,
            }
        )
    out.sort(key=lambda item: item["amount_minor"], reverse=True)
    return out


def cash_flow(
    db: Session, user_id: int, months: int, currency: str, anchor: date | None = None
) -> list[dict[str, Any]]:
    anchor = anchor or date.today()
    first_month = anchor.replace(day=1) - relativedelta(months=months - 1)

    rows = db.scalars(
        select(Transaction).where(
            Transaction.user_id == user_id,
            Transaction.booked_at >= first_month,
            Transaction.booked_at <= anchor,
            Transaction.currency == currency,
            Transaction.is_transfer.is_(False),
        )
    ).all()

    buckets: dict[str, MoneyTotals] = {}
    for offset in range(months):
        key = (first_month + relativedelta(months=offset)).strftime("%Y-%m")
        buckets[key] = MoneyTotals()

    for txn in rows:
        key = txn.booked_at.strftime("%Y-%m")
        if key not in buckets:
            continue
        if txn.amount_minor >= 0:
            buckets[key].income_minor += txn.amount_minor
        else:
            buckets[key].expense_minor += -txn.amount_minor

    return [
        {
            "month": key,
            "income_minor": totals.income_minor,
            "expense_minor": totals.expense_minor,
            "net_minor": totals.net_minor,
        }
        for key, totals in buckets.items()
    ]


def net_worth(db: Session, user_id: int) -> dict[str, Any]:
    accounts = db.scalars(
        select(Account).where(
            Account.user_id == user_id,
            Account.is_active.is_(True),
            Account.include_in_net_worth.is_(True),
        )
    ).all()
    per_currency: dict[str, int] = defaultdict(int)
    for account in accounts:
        per_currency[account.currency] += account.balance_minor
    return {
        "balances": [
            {"currency": currency, "amount_minor": amount}
            for currency, amount in sorted(per_currency.items())
        ],
        "account_count": len(accounts),
    }


def top_merchants(
    db: Session, user_id: int, start: date, end: date, currency: str, limit: int = 10
) -> list[dict[str, Any]]:
    rows = db.execute(
        select(
            func.coalesce(Transaction.merchant, Transaction.description),
            func.sum(Transaction.amount_minor),
            func.count(Transaction.id),
        )
        .where(
            Transaction.user_id == user_id,
            Transaction.booked_at >= start,
            Transaction.booked_at <= end,
            Transaction.currency == currency,
            Transaction.is_transfer.is_(False),
            Transaction.amount_minor < 0,
        )
        .group_by(func.coalesce(Transaction.merchant, Transaction.description))
        .order_by(func.sum(Transaction.amount_minor).asc())
        .limit(limit)
    ).all()
    return [
        {"name": name or "Unknown", "amount_minor": -int(amount), "transaction_count": count}
        for name, amount, count in rows
    ]


def budget_progress(
    db: Session, user_id: int, anchor: date | None = None
) -> list[dict[str, Any]]:
    anchor = anchor or date.today()
    start, end = month_bounds(anchor)
    budgets = db.scalars(select(Budget).where(Budget.user_id == user_id)).all()
    if not budgets:
        return []

    spend_rows = db.execute(
        select(Transaction.category_id, Transaction.currency, func.sum(Transaction.amount_minor))
        .where(
            Transaction.user_id == user_id,
            Transaction.booked_at >= start,
            Transaction.booked_at <= end,
            Transaction.is_transfer.is_(False),
            Transaction.amount_minor < 0,
        )
        .group_by(Transaction.category_id, Transaction.currency)
    ).all()
    spend = {(category_id, currency): -int(amount) for category_id, currency, amount in spend_rows}

    days_in_month = (end - start).days + 1
    days_elapsed = min((date.today() - start).days + 1, days_in_month) if date.today() >= start else 0

    out = []
    for budget in budgets:
        spent = spend.get((budget.category_id, budget.currency), 0)
        limit = budget.amount_minor
        pace = round(limit * days_elapsed / days_in_month) if days_in_month else 0
        out.append(
            {
                "budget_id": budget.id,
                "category_id": budget.category_id,
                "category_name": budget.category.name if budget.category else "Unknown",
                "colour": budget.category.colour if budget.category else "#9e9e9e",
                "currency": budget.currency,
                "limit_minor": limit,
                "spent_minor": spent,
                "remaining_minor": limit - spent,
                "used_share": round(spent / limit, 4) if limit else 0.0,
                "on_pace": spent <= pace,
                "expected_by_now_minor": pace,
            }
        )
    out.sort(key=lambda item: item["used_share"], reverse=True)
    return out


def detect_recurring(
    db: Session, user_id: int, lookback_days: int = 400, currency: str | None = None
) -> list[dict[str, Any]]:
    """Group outgoing transactions by merchant and flag steady monthly-ish patterns."""
    since = date.today() - timedelta(days=lookback_days)
    query = select(Transaction).where(
        Transaction.user_id == user_id,
        Transaction.booked_at >= since,
        Transaction.amount_minor < 0,
        Transaction.is_transfer.is_(False),
    )
    if currency:
        query = query.where(Transaction.currency == currency)

    groups: dict[tuple[str, str], list[Transaction]] = defaultdict(list)
    for txn in db.scalars(query).all():
        key = (_normalise_merchant(txn.merchant or txn.description), txn.currency)
        if key[0]:
            groups[key].append(txn)

    out: list[dict[str, Any]] = []
    for (name, txn_currency), items in groups.items():
        if len(items) < 3:
            continue
        items.sort(key=lambda t: t.booked_at)
        gaps = [
            (items[i].booked_at - items[i - 1].booked_at).days for i in range(1, len(items))
        ]
        gaps = [gap for gap in gaps if gap > 0]
        if len(gaps) < 2:
            continue

        typical_gap = median(gaps)
        if not 5 <= typical_gap <= 400:
            continue
        # Consistent cadence: most gaps close to the median.
        consistent = sum(1 for gap in gaps if abs(gap - typical_gap) <= max(4, typical_gap * 0.35))
        if consistent / len(gaps) < 0.6:
            continue

        amounts = [-t.amount_minor for t in items]
        typical_amount = int(median(amounts))
        if typical_amount == 0:
            continue
        spread = max(amounts) - min(amounts)
        if spread > max(typical_amount * 0.5, 300):  # allow 50% or 3.00 of variation
            continue

        cadence = _cadence_label(typical_gap)
        last_seen = items[-1].booked_at
        out.append(
            {
                "name": name.title(),
                "currency": txn_currency,
                "typical_amount_minor": typical_amount,
                "cadence": cadence,
                "cadence_days": int(typical_gap),
                "occurrences": len(items),
                "last_seen": last_seen.isoformat(),
                "next_expected": (last_seen + timedelta(days=int(typical_gap))).isoformat(),
                "annualised_minor": _annualise(typical_amount, typical_gap),
                "category_id": items[-1].category_id,
            }
        )

    out.sort(key=lambda item: item["annualised_minor"], reverse=True)
    return out


def _annualise(amount_minor: int, gap_days: float) -> int:
    if gap_days <= 0:
        return 0
    return int(round(amount_minor * (365 / gap_days)))


def _cadence_label(gap_days: float) -> str:
    if gap_days <= 9:
        return "weekly"
    if gap_days <= 18:
        return "fortnightly"
    if gap_days <= 45:
        return "monthly"
    if gap_days <= 120:
        return "quarterly"
    if gap_days <= 200:
        return "half-yearly"
    return "yearly"


def _normalise_merchant(value: str) -> str:
    cleaned = "".join(char for char in (value or "").lower() if char.isalnum() or char.isspace())
    tokens = [token for token in cleaned.split() if not token.isdigit() and len(token) > 1]
    return " ".join(tokens[:3]).strip()


def insights(
    db: Session, user_id: int, base_currency: str, anchor: date | None = None
) -> list[dict[str, Any]]:
    """A handful of plain-language observations for the dashboard."""
    anchor = anchor or date.today()
    this_start, this_end = month_bounds(anchor)
    last_start, last_end = month_bounds(this_start - timedelta(days=1))

    this_month = period_summary(db, user_id, this_start, this_end, base_currency)
    last_month = period_summary(db, user_id, last_start, last_end, base_currency)

    out: list[dict[str, Any]] = []

    if last_month["expense_minor"] and this_month["projected_month_spend_minor"]:
        delta = this_month["projected_month_spend_minor"] - last_month["expense_minor"]
        share = delta / last_month["expense_minor"]
        if abs(share) >= 0.1:
            out.append(
                {
                    "kind": "trend",
                    "severity": "warning" if share > 0 else "positive",
                    "title": (
                        f"Spending is tracking {abs(share):.0%} "
                        f"{'above' if share > 0 else 'below'} last month"
                    ),
                    "detail": (
                        f"Projected {_fmt(this_month['projected_month_spend_minor'], base_currency)} "
                        f"vs {_fmt(last_month['expense_minor'], base_currency)} last month."
                    ),
                    "amount_minor": abs(delta),
                }
            )

    this_categories = {row["name"]: row for row in spend_by_category(db, user_id, this_start, this_end, base_currency)}
    last_categories = {row["name"]: row for row in spend_by_category(db, user_id, last_start, last_end, base_currency)}
    for name, row in list(this_categories.items())[:6]:
        previous = last_categories.get(name)
        if not previous or previous["amount_minor"] < 2000:
            continue
        delta = row["amount_minor"] - previous["amount_minor"]
        share = delta / previous["amount_minor"]
        if share >= 0.4:
            out.append(
                {
                    "kind": "category",
                    "severity": "warning",
                    "title": f"{name} is up {share:.0%} this month",
                    "detail": (
                        f"{_fmt(row['amount_minor'], base_currency)} so far, "
                        f"vs {_fmt(previous['amount_minor'], base_currency)} last month."
                    ),
                    "amount_minor": delta,
                }
            )

    for budget in budget_progress(db, user_id, anchor):
        if budget["used_share"] >= 1:
            out.append(
                {
                    "kind": "budget",
                    "severity": "alert",
                    "title": f"{budget['category_name']} budget is blown",
                    "detail": (
                        f"{_fmt(budget['spent_minor'], budget['currency'])} of "
                        f"{_fmt(budget['limit_minor'], budget['currency'])}."
                    ),
                    "amount_minor": -budget["remaining_minor"],
                }
            )
        elif not budget["on_pace"] and budget["used_share"] >= 0.5:
            out.append(
                {
                    "kind": "budget",
                    "severity": "warning",
                    "title": f"{budget['category_name']} is ahead of pace",
                    "detail": (
                        f"{budget['used_share']:.0%} used with "
                        f"{_fmt(budget['remaining_minor'], budget['currency'])} left."
                    ),
                    "amount_minor": budget["spent_minor"],
                }
            )

    recurring = detect_recurring(db, user_id, currency=base_currency)
    if recurring:
        annual = sum(item["annualised_minor"] for item in recurring)
        out.append(
            {
                "kind": "recurring",
                "severity": "info",
                "title": f"{len(recurring)} recurring payments detected",
                "detail": f"About {_fmt(annual, base_currency)} a year, "
                f"led by {recurring[0]['name']}.",
                "amount_minor": annual,
            }
        )

    if this_month["net_minor"] < 0 and this_month["income_minor"] > 0:
        out.append(
            {
                "kind": "cashflow",
                "severity": "alert",
                "title": "Spending more than you earned this month",
                "detail": f"Net {_fmt(this_month['net_minor'], base_currency)} so far.",
                "amount_minor": this_month["net_minor"],
            }
        )

    return out


def _fmt(amount_minor: int, currency: str) -> str:
    symbol = {"GBP": "£", "EUR": "€", "USD": "$"}.get(currency, f"{currency} ")
    return f"{symbol}{amount_minor / 100:,.2f}"


def category_kinds(db: Session, user_id: int) -> dict[int, CategoryKind]:
    return {
        category.id: category.kind
        for category in db.scalars(select(Category).where(Category.user_id == user_id)).all()
    }


def daterange_months(months: int, anchor: date | None = None) -> Iterable[date]:
    anchor = anchor or date.today()
    start = anchor.replace(day=1) - relativedelta(months=months - 1)
    for offset in range(months):
        yield start + relativedelta(months=offset)
