"""Chat over your own finances.

Claude gets a set of read-only tools that query this database and nothing else.
It cannot move money, edit a transaction, or reach the internet — the tools below
are the entire surface it can touch. Every answer is therefore grounded in your
real figures, and the UI shows which tools were consulted.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any

from anthropic import beta_tool
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..models import Account, Category, Transaction, User
from ..providers.base import from_minor
from ..services import analytics as svc
from .ai import AIError, call_with_fallback_retry, friendly_error, get_client, text_of

logger = logging.getLogger(__name__)

MAX_TOOL_ITERATIONS = 12
MAX_ROWS = 60

SYSTEM_PROMPT = """You are the analyst inside a personal finance app, answering \
questions about one person's own bank data. You are talking to the person whose \
money it is.

You have read-only tools over their real transactions. Use them — never answer a \
factual question about their money from memory or assumption. If a question needs \
figures you have not fetched, fetch them first. Call several tools in one turn when \
the question needs more than one angle.

How to answer:
- Lead with the answer. The first sentence should be the number or the finding, not \
a description of what you are about to do.
- Quote real figures with their currency symbol, and say what period they cover.
- Totals are grouped per currency and never converted between them. If an answer \
spans currencies, report them separately rather than adding them together.
- Keep it short — a few sentences, or a small table when comparing several things. \
No headings for a one-line answer.
- Money out is stored as a negative amount; money in is positive.
- Transfers between the person's own accounts are excluded from spending totals by \
default, because they are not spending.

Be honest about limits. If the data does not cover what was asked — the period is \
empty, the category has no transactions, the accounts have not been synced — say so \
plainly instead of estimating. Never invent a figure a tool did not return.

Answer what was asked. Do not append budgeting advice, savings tips, or moral \
commentary about their purchases unless they ask for it."""


@dataclass(slots=True)
class ToolCall:
    name: str
    input: dict[str, Any]

    def as_dict(self) -> dict[str, Any]:
        return {"name": self.name, "input": self.input}


@dataclass(slots=True)
class ChatResult:
    reply: str
    tool_calls: list[ToolCall] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0

    def as_dict(self) -> dict[str, Any]:
        return {
            "reply": self.reply,
            "tool_calls": [call.as_dict() for call in self.tool_calls],
            "usage": {"input_tokens": self.input_tokens, "output_tokens": self.output_tokens},
        }


def _money(amount_minor: int, currency: str) -> str:
    return f"{from_minor(amount_minor, currency):.2f} {currency}"


def _resolve_period(start: str | None, end: str | None, months_back: int | None) -> tuple[date, date]:
    today = date.today()
    if start and end:
        return date.fromisoformat(start), date.fromisoformat(end)
    if months_back:
        from dateutil.relativedelta import relativedelta

        first = (today.replace(day=1) - relativedelta(months=months_back - 1))
        return first, today
    return svc.month_bounds(today)


def build_tools(db: Session, user: User) -> list[Any]:
    """Bind the read-only query tools to this user's data."""
    user_id = user.id
    base_currency = user.base_currency

    @beta_tool
    def search_transactions(
        query: str = "",
        start_date: str = "",
        end_date: str = "",
        category: str = "",
        direction: str = "",
        min_amount: float = 0.0,
        limit: int = 25,
    ) -> str:
        """Search the person's transactions and return the matching rows.

        Use this for questions about specific purchases, merchants, or dates
        ("how much did I spend at Tesco", "what was that big payment in March").

        Args:
            query: Text to match against merchant, description, counterparty or reference. Empty matches everything.
            start_date: Earliest booking date, ISO format YYYY-MM-DD. Empty means no lower bound.
            end_date: Latest booking date, ISO format YYYY-MM-DD. Empty means no upper bound.
            category: Exact category name to filter by. Empty means all categories.
            direction: "out" for spending, "in" for money received, empty for both.
            min_amount: Only return transactions whose absolute value is at least this, in major units.
            limit: Maximum rows to return, capped at 60.
        """
        statement = select(Transaction).where(Transaction.user_id == user_id)
        if query:
            needle = f"%{query.lower()}%"
            statement = statement.where(
                or_(
                    func.lower(Transaction.description).like(needle),
                    func.lower(func.coalesce(Transaction.merchant, "")).like(needle),
                    func.lower(func.coalesce(Transaction.counterparty, "")).like(needle),
                    func.lower(func.coalesce(Transaction.reference, "")).like(needle),
                )
            )
        if start_date:
            statement = statement.where(Transaction.booked_at >= date.fromisoformat(start_date))
        if end_date:
            statement = statement.where(Transaction.booked_at <= date.fromisoformat(end_date))
        if category:
            matched = db.scalar(
                select(Category).where(
                    Category.user_id == user_id, func.lower(Category.name) == category.lower()
                )
            )
            if matched is None:
                names = [
                    row.name
                    for row in db.scalars(select(Category).where(Category.user_id == user_id)).all()
                ]
                return f"No category named '{category}'. Available: {', '.join(sorted(names))}"
            statement = statement.where(Transaction.category_id == matched.id)
        if direction == "out":
            statement = statement.where(Transaction.amount_minor < 0)
        elif direction == "in":
            statement = statement.where(Transaction.amount_minor > 0)
        if min_amount:
            statement = statement.where(
                func.abs(Transaction.amount_minor) >= int(round(min_amount * 100))
            )

        total = db.scalar(select(func.count()).select_from(statement.subquery())) or 0
        rows = db.scalars(
            statement.order_by(Transaction.booked_at.desc(), Transaction.id.desc()).limit(
                min(max(limit, 1), MAX_ROWS)
            )
        ).all()
        if not rows:
            return "No transactions matched that search."

        categories = {
            category_row.id: category_row.name
            for category_row in db.scalars(
                select(Category).where(Category.user_id == user_id)
            ).all()
        }
        per_currency: dict[str, int] = {}
        lines = [f"{total} transaction(s) matched; showing {len(rows)}."]
        for txn in rows:
            per_currency[txn.currency] = per_currency.get(txn.currency, 0) + txn.amount_minor
            lines.append(
                f"{txn.booked_at.isoformat()} | {_money(txn.amount_minor, txn.currency)} | "
                f"{(txn.merchant or txn.description)[:60]} | "
                f"{categories.get(txn.category_id, 'Uncategorised')}"
                + (" | transfer" if txn.is_transfer else "")
            )
        shown = ", ".join(_money(amount, currency) for currency, amount in sorted(per_currency.items()))
        lines.append(f"Net of the rows shown: {shown}")
        return "\n".join(lines)

    @beta_tool
    def spending_summary(start_date: str = "", end_date: str = "", months_back: int = 0) -> str:
        """Totals for money in, money out and net over a period.

        Defaults to the current calendar month if no period is given.

        Args:
            start_date: Start of the period, ISO YYYY-MM-DD.
            end_date: End of the period, ISO YYYY-MM-DD.
            months_back: Instead of dates, cover this many months up to today.
        """
        start, end = _resolve_period(start_date or None, end_date or None, months_back or None)
        summary = svc.period_summary(db, user_id, start, end, base_currency)
        lines = [
            f"Period {summary['start']} to {summary['end']} ({base_currency}):",
            f"  money in: {_money(summary['income_minor'], base_currency)}",
            f"  money out: {_money(summary['expense_minor'], base_currency)}",
            f"  net: {_money(summary['net_minor'], base_currency)}",
            f"  transactions: {summary['transaction_count']}",
            f"  average daily spend: {_money(summary['average_daily_spend_minor'], base_currency)}",
            f"  projected spend for the full period: "
            f"{_money(summary['projected_month_spend_minor'], base_currency)}",
        ]
        for other in summary["other_currencies"]:
            lines.append(
                f"  also held in {other['currency']}: out "
                f"{_money(other['expense_minor'], other['currency'])}, in "
                f"{_money(other['income_minor'], other['currency'])} (not converted)"
            )
        return "\n".join(lines)

    @beta_tool
    def category_breakdown(start_date: str = "", end_date: str = "", months_back: int = 0) -> str:
        """Spending split by category over a period, largest first.

        Args:
            start_date: Start of the period, ISO YYYY-MM-DD.
            end_date: End of the period, ISO YYYY-MM-DD.
            months_back: Instead of dates, cover this many months up to today.
        """
        start, end = _resolve_period(start_date or None, end_date or None, months_back or None)
        rows = svc.spend_by_category(db, user_id, start, end, base_currency)
        if not rows:
            return f"No spending recorded in {base_currency} between {start} and {end}."
        lines = [f"Spending by category, {start} to {end} ({base_currency}):"]
        for row in rows:
            lines.append(
                f"  {row['name']}: {_money(row['amount_minor'], base_currency)} "
                f"({row['share'] * 100:.1f}%, {row['transaction_count']} transactions)"
            )
        return "\n".join(lines)

    @beta_tool
    def cash_flow(months: int = 6) -> str:
        """Money in and money out for each of the last N calendar months.

        Use this for trend questions ("am I spending more than I used to").

        Args:
            months: How many months to cover, between 2 and 24.
        """
        rows = svc.cash_flow(db, user_id, min(max(months, 2), 24), base_currency)
        lines = [f"Monthly cash flow ({base_currency}):"]
        for row in rows:
            lines.append(
                f"  {row['month']}: in {_money(row['income_minor'], base_currency)}, "
                f"out {_money(row['expense_minor'], base_currency)}, "
                f"net {_money(row['net_minor'], base_currency)}"
            )
        return "\n".join(lines)

    @beta_tool
    def recurring_payments() -> str:
        """Subscriptions and other payments that repeat on a steady schedule.

        Detected from the person's own history: three or more payments to the same
        merchant at a consistent interval and a stable amount.
        """
        rows = svc.detect_recurring(db, user_id, currency=base_currency)
        if not rows:
            return "No recurring payments detected yet."
        lines = [f"{len(rows)} recurring payment(s) detected:"]
        for row in rows:
            lines.append(
                f"  {row['name']}: {_money(row['typical_amount_minor'], row['currency'])} "
                f"{row['cadence']}, about {_money(row['annualised_minor'], row['currency'])}/year, "
                f"last seen {row['last_seen']}, next expected {row['next_expected']}"
            )
        return "\n".join(lines)

    @beta_tool
    def budgets_status() -> str:
        """How this month's spending compares with the budgets that are set."""
        rows = svc.budget_progress(db, user_id)
        if not rows:
            return "No budgets have been set."
        lines = ["Budget status for this month:"]
        for row in rows:
            lines.append(
                f"  {row['category_name']}: spent {_money(row['spent_minor'], row['currency'])} "
                f"of {_money(row['limit_minor'], row['currency'])} "
                f"({row['used_share'] * 100:.0f}% used, "
                f"{'on pace' if row['on_pace'] else 'ahead of pace'})"
            )
        return "\n".join(lines)

    @beta_tool
    def accounts_overview() -> str:
        """Current balances of the person's accounts, grouped by currency."""
        accounts = db.scalars(
            select(Account).where(Account.user_id == user_id, Account.is_active.is_(True))
        ).all()
        if not accounts:
            return "No accounts have been connected or created yet."
        lines = ["Accounts:"]
        for account in accounts:
            lines.append(
                f"  {account.name}: {_money(account.balance_minor, account.currency)}"
                + (f" (synced {account.last_synced_at:%Y-%m-%d})" if account.last_synced_at else "")
            )
        worth = svc.net_worth(db, user_id)
        totals = ", ".join(
            _money(row["amount_minor"], row["currency"]) for row in worth["balances"]
        )
        lines.append(f"Total by currency: {totals}")
        return "\n".join(lines)

    @beta_tool
    def top_merchants(start_date: str = "", end_date: str = "", months_back: int = 0, limit: int = 10) -> str:
        """The merchants the person spent the most with over a period.

        Args:
            start_date: Start of the period, ISO YYYY-MM-DD.
            end_date: End of the period, ISO YYYY-MM-DD.
            months_back: Instead of dates, cover this many months up to today.
            limit: How many merchants to return, at most 30.
        """
        start, end = _resolve_period(start_date or None, end_date or None, months_back or None)
        rows = svc.top_merchants(db, user_id, start, end, base_currency, min(max(limit, 1), 30))
        if not rows:
            return f"No spending recorded between {start} and {end}."
        lines = [f"Top merchants, {start} to {end} ({base_currency}):"]
        for row in rows:
            lines.append(
                f"  {row['name']}: {_money(row['amount_minor'], base_currency)} "
                f"across {row['transaction_count']} transactions"
            )
        return "\n".join(lines)

    return [
        search_transactions,
        spending_summary,
        category_breakdown,
        cash_flow,
        recurring_payments,
        budgets_status,
        accounts_overview,
        top_merchants,
    ]


def _context_preamble(db: Session, user: User) -> str:
    today = date.today()
    account_count = db.scalar(
        select(func.count(Account.id)).where(
            Account.user_id == user.id, Account.is_active.is_(True)
        )
    )
    txn_count = db.scalar(
        select(func.count(Transaction.id)).where(Transaction.user_id == user.id)
    )
    earliest = db.scalar(
        select(func.min(Transaction.booked_at)).where(Transaction.user_id == user.id)
    )
    return (
        f"Today is {today.isoformat()}. The person's base currency is {user.base_currency}. "
        f"They have {account_count or 0} active account(s) and {txn_count or 0} stored "
        f"transactions"
        + (f", the earliest dated {earliest}." if earliest else ".")
    )


def chat(
    db: Session,
    user: User,
    history: list[dict[str, Any]],
    settings: Settings | None = None,
) -> ChatResult:
    """Run one turn of the finance chat, letting Claude query the database."""
    settings = settings or get_settings()
    client = get_client(settings)
    tools = build_tools(db, user)

    system = [
        {"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}},
        {"type": "text", "text": _context_preamble(db, user)},
    ]

    def make_request(extra: dict[str, Any]):
        return client.beta.messages.tool_runner(
            model=settings.ai_model,
            max_tokens=16000,
            system=system,
            tools=tools,
            messages=history,
            max_iterations=MAX_TOOL_ITERATIONS,
            **extra,
        )

    calls: list[ToolCall] = []
    final = None
    input_tokens = output_tokens = 0

    try:
        runner = call_with_runner_retry(make_request, settings)
        for message in runner:
            final = message
            for block in message.content:
                if block.type == "tool_use":
                    calls.append(ToolCall(name=block.name, input=dict(block.input or {})))
            usage = getattr(message, "usage", None)
            if usage:
                input_tokens += getattr(usage, "input_tokens", 0) or 0
                output_tokens += getattr(usage, "output_tokens", 0) or 0
    except AIError:
        raise
    except Exception as exc:  # noqa: BLE001 - surfaced to the caller as a 502
        logger.exception("Finance chat failed")
        raise friendly_error(exc) from exc

    if final is None:
        raise AIError("The assistant returned no response.")

    reply = text_of(final)
    if not reply:
        reply = (
            "I looked at your data but could not put an answer together. "
            "Try asking in a different way."
        )
    return ChatResult(reply=reply, tool_calls=calls, input_tokens=input_tokens, output_tokens=output_tokens)


def call_with_runner_retry(make_request, settings: Settings):
    """The tool runner takes the same model-dependent parameters as any request."""
    return call_with_fallback_retry(make_request, settings)


def serialise_history(messages: list[Any]) -> str:
    """Store a conversation as JSON text."""
    return json.dumps(messages, default=str)


def trim_history(history: list[dict[str, Any]], max_turns: int = 20) -> list[dict[str, Any]]:
    """Keep the conversation bounded; the first message must be from the user."""
    trimmed = history[-max_turns:]
    while trimmed and trimmed[0].get("role") != "user":
        trimmed.pop(0)
    return trimmed


def recent_window_hint() -> str:
    """Used by the UI to seed suggested questions."""
    start, _ = svc.month_bounds(date.today())
    return (start - timedelta(days=1)).strftime("%B")
