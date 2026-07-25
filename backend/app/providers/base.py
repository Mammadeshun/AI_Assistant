"""Provider abstraction.

A provider knows how to authorise against an institution and pull normalised
accounts and transactions. Everything above this layer is provider-agnostic,
so adding Monzo/Starling/Plaid later means writing one more module here.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Callable, Protocol


class ProviderError(RuntimeError):
    """Recoverable provider failure — surfaced to the user as a sync error."""


class ConsentExpired(ProviderError):
    """The user must re-authorise the connection."""


@dataclass(slots=True)
class NormalisedAccount:
    external_id: str
    name: str
    currency: str
    balance_minor: int
    account_type: str | None = None
    available_minor: int | None = None
    iban_last4: str | None = None


@dataclass(slots=True)
class NormalisedTransaction:
    external_id: str | None
    booked_at: date
    amount_minor: int
    currency: str
    description: str
    value_date: date | None = None
    merchant: str | None = None
    counterparty: str | None = None
    reference: str | None = None
    provider_category: str | None = None
    state: str = "completed"
    raw: dict[str, Any] = field(default_factory=dict)

    def dedupe_key(self) -> str:
        """Stable identity for a transaction within one account.

        Providers that expose a stable id get keyed on it. The rest fall back to
        a content hash, which is what makes re-importing an overlapping window
        idempotent.
        """
        if self.external_id:
            return hashlib.sha256(f"id:{self.external_id}".encode()).hexdigest()[:32]
        basis = "|".join(
            [
                self.booked_at.isoformat(),
                str(self.amount_minor),
                self.currency,
                " ".join((self.description or "").lower().split()),
                (self.reference or "").lower(),
            ]
        )
        return hashlib.sha256(basis.encode()).hexdigest()[:32]


def assign_occurrence_ids(transactions: list[NormalisedTransaction], prefix: str) -> None:
    """Give rows from a statement file a stable identity of their own.

    A statement can legitimately list the same purchase twice on one day — two
    identical coffees — and the content hash alone treats them as one row, so
    the second is lost on import. Numbering the repeats within their day keeps
    them distinct without making the id depend on anything that changes between
    exports, so re-importing an overlapping statement still recognises the rows
    it already has.
    """
    seen: dict[str, int] = {}
    for txn in transactions:
        if txn.external_id:
            continue
        basis = "|".join(
            [
                txn.booked_at.isoformat(),
                str(txn.amount_minor),
                txn.currency,
                " ".join((txn.description or "").lower().split()),
            ]
        )
        occurrence = seen.get(basis, 0)
        seen[basis] = occurrence + 1
        digest = hashlib.sha256(f"{basis}|{occurrence}".encode()).hexdigest()[:24]
        txn.external_id = f"{prefix}:{digest}"


# Called by a provider when it rotates tokens, so the caller can persist them.
CredentialSink = Callable[[dict[str, Any]], None]


class BankProvider(Protocol):
    """Interface every provider implements."""

    key: str
    display_name: str

    async def fetch_accounts(self) -> list[NormalisedAccount]:
        ...

    async def fetch_transactions(
        self, account_external_id: str, since: date, until: date | None = None
    ) -> list[NormalisedTransaction]:
        ...


def to_minor(amount: float | int | str, currency: str = "GBP") -> int:
    """Convert a decimal money amount to integer minor units.

    Uses Decimal + explicit rounding so 0.1 + 0.2 style float error can't reach
    the database.
    """
    from decimal import Decimal, ROUND_HALF_UP

    exponent = _CURRENCY_EXPONENT.get(currency.upper(), 2)
    quant = Decimal(1).scaleb(-exponent)
    value = Decimal(str(amount)).quantize(quant, rounding=ROUND_HALF_UP)
    return int(value.scaleb(exponent))


def from_minor(amount_minor: int, currency: str = "GBP") -> float:
    exponent = _CURRENCY_EXPONENT.get(currency.upper(), 2)
    return amount_minor / (10**exponent)


# Currencies whose minor unit is not 1/100.
_CURRENCY_EXPONENT: dict[str, int] = {
    "JPY": 0,
    "KRW": 0,
    "VND": 0,
    "CLP": 0,
    "ISK": 0,
    "HUF": 0,
    "BHD": 3,
    "KWD": 3,
    "OMR": 3,
    "TND": 3,
    "JOD": 3,
}
