"""CSV import.

Works with the statement Revolut exports from the app ("Download statement" ->
CSV) and with generic date/description/amount files. Useful as a fallback when
you don't want an API connection at all, and for backfilling history beyond the
window an aggregator gives you.
"""
from __future__ import annotations

import csv
import io
from datetime import date, datetime
from typing import Any, Iterable

from .base import NormalisedTransaction, ProviderError, to_minor

# Header aliases, lowercased. Revolut's own export uses the first of each group.
_DATE_FIELDS = ("completed date", "started date", "date", "booking date", "date completed", "value date")
_DESCRIPTION_FIELDS = ("description", "reference", "details", "narrative", "name")
_AMOUNT_FIELDS = ("amount", "value", "money in/out")
_PAID_OUT_FIELDS = ("paid out", "paid out (gbp)", "debit", "withdrawal", "money out")
_PAID_IN_FIELDS = ("paid in", "paid in (gbp)", "credit", "deposit", "money in")
_CURRENCY_FIELDS = ("currency", "ccy")
_FEE_FIELDS = ("fee",)
_TYPE_FIELDS = ("type", "transaction type", "category")
_STATE_FIELDS = ("state", "status")

_DATE_FORMATS = (
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d %H:%M",
    "%Y-%m-%d",
    "%d/%m/%Y %H:%M:%S",
    "%d/%m/%Y",
    "%m/%d/%Y",
    "%d-%m-%Y",
    "%d %b %Y",
    "%d %B %Y",
)


def parse_csv(content: bytes | str, default_currency: str = "GBP") -> list[NormalisedTransaction]:
    text = content.decode("utf-8-sig") if isinstance(content, bytes) else content
    if not text.strip():
        raise ProviderError("The uploaded file is empty.")

    try:
        dialect = csv.Sniffer().sniff(text[:4096], delimiters=",;\t")
    except csv.Error:
        dialect = csv.excel

    reader = csv.DictReader(io.StringIO(text), dialect=dialect)
    if not reader.fieldnames:
        raise ProviderError("Could not read a header row from the CSV.")

    lookup = {(name or "").strip().lower(): name for name in reader.fieldnames}
    date_key = _first_match(lookup, _DATE_FIELDS)
    if not date_key:
        raise ProviderError(
            f"No date column found. Columns seen: {', '.join(reader.fieldnames)}"
        )
    description_key = _first_match(lookup, _DESCRIPTION_FIELDS)
    amount_key = _first_match(lookup, _AMOUNT_FIELDS)
    paid_out_key = _first_match(lookup, _PAID_OUT_FIELDS)
    paid_in_key = _first_match(lookup, _PAID_IN_FIELDS)
    if not amount_key and not (paid_out_key or paid_in_key):
        raise ProviderError(
            "No amount column found (expected 'Amount', or 'Paid Out'/'Paid In')."
        )
    currency_key = _first_match(lookup, _CURRENCY_FIELDS)
    fee_key = _first_match(lookup, _FEE_FIELDS)
    type_key = _first_match(lookup, _TYPE_FIELDS)
    state_key = _first_match(lookup, _STATE_FIELDS)

    out: list[NormalisedTransaction] = []
    for line_no, row in enumerate(reader, start=2):
        if not any((value or "").strip() for value in row.values()):
            continue

        booked_at = _parse_date(row.get(date_key))
        if booked_at is None:
            continue  # skip footer/summary rows rather than fail the whole import

        currency = (row.get(currency_key) or default_currency).strip().upper()[:3] or default_currency

        if amount_key:
            amount = _parse_amount(row.get(amount_key))
        else:
            paid_in = _parse_amount(row.get(paid_in_key)) if paid_in_key else 0.0
            paid_out = _parse_amount(row.get(paid_out_key)) if paid_out_key else 0.0
            amount = abs(paid_in) - abs(paid_out)
        if amount is None:
            continue

        fee = _parse_amount(row.get(fee_key)) if fee_key else 0.0
        amount_minor = to_minor(amount, currency) - to_minor(abs(fee or 0.0), currency)

        state_raw = (row.get(state_key) or "").strip().lower() if state_key else ""
        if state_raw in {"reverted", "declined", "failed"}:
            continue

        description = (row.get(description_key) or "").strip() if description_key else ""
        out.append(
            NormalisedTransaction(
                external_id=None,  # CSV rows have no stable id; dedupe on content
                booked_at=booked_at,
                value_date=booked_at,
                amount_minor=amount_minor,
                currency=currency,
                description=description or "Imported transaction",
                merchant=description or None,
                provider_category=(row.get(type_key) or None) if type_key else None,
                state="pending" if state_raw == "pending" else "completed",
                raw={"csv_line": line_no},
            )
        )

    if not out:
        raise ProviderError("No transactions could be parsed from that file.")
    return out


def _first_match(lookup: dict[str, str], candidates: Iterable[str]) -> str | None:
    for candidate in candidates:
        if candidate in lookup:
            return lookup[candidate]
    return None


def _parse_date(value: Any) -> date | None:
    if not value:
        return None
    raw = str(value).strip()
    if not raw:
        return None
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(raw, fmt).date()
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00")).date()
    except ValueError:
        return None


def _parse_amount(value: Any) -> float | None:
    if value is None:
        return None
    raw = str(value).strip()
    if not raw:
        return 0.0
    negative = raw.startswith("(") and raw.endswith(")")
    cleaned = raw.strip("()")
    for symbol in ("£", "$", "€", " ", " ", ","):
        cleaned = cleaned.replace(symbol, "")
    if not cleaned or cleaned in {"-", "."}:
        return 0.0
    try:
        amount = float(cleaned)
    except ValueError:
        return None
    return -amount if negative else amount
