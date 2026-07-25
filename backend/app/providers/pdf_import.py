"""Revolut PDF statement import.

The Revolut app offers a PDF statement by default; the CSV is buried behind a
format choice, so the PDF is what most people actually have. This reads the
transaction tables out of it.

Two things about those PDFs need handling. The euro sign often survives text
extraction as mojibake (`â‚¬`), and some dates are rendered `########` — the
spreadsheet symptom of a column too narrow to print, baked into the PDF by
whatever produced it. Those rows still carry a valid merchant and amount, so
they're kept with the date carried forward from the previous row and a note
saying so, rather than thrown away.
"""
from __future__ import annotations

import io
import re
from datetime import date, datetime
from typing import Iterable

from .base import (
    NormalisedTransaction,
    ProviderError,
    assign_occurrence_ids,
    to_minor,
)

# Row: a date (or ######## where the date wouldn't fit), then the description,
# the transaction type, the amount, the running balance, and fee columns.
ROW = re.compile(r"^(?P<date>\d{1,2}-[A-Za-z]{3}-\d{2}|#{3,})\s+(?P<rest>.+)$")

# Amounts print as -€56.00 or €2,630.29, frequently run together with the next
# column: €2,630.29€0.00
MONEY = re.compile(r"(?P<sign>-?)(?P<symbol>[€$£])\s?(?P<value>[\d,]+\.\d{2})")

# Revolut appends the type to the description with no separating space
# ("Farmacia FormaggiaMerchant"), so it has to be stripped off the end.
ROW_TYPES = tuple(
    sorted(
        (
            "Merchant",
            "Others",
            "Exchange",
            "Cashback",
            "Interest",
            "Top-Up",
            "Top-up",
            "Transfer",
            "Card",
            "ATM",
            "Fees",
            "Fee",
        ),
        key=len,
        reverse=True,  # longest first, so "Fees" wins over "Fee"
    )
)

SYMBOL_CURRENCY = {"€": "EUR", "£": "GBP", "$": "USD"}

# Lines that look like rows but are summaries or headings.
SKIP_PREFIXES = ("Total", "Opening balance", "Closing balance", "Date Description")

# The three lead characters every mangled sequence starts with, followed by the
# bytes that continue it. Matching runs rather than whole lines lets one
# unrecoverable character be left alone instead of costing the rest of the line.
MOJIBAKE_RUN = re.compile(
    "[ÂÃâ][\u0080-\u00ff\u0152\u0153\u0160\u0161\u0178\u017d\u017e"
    "\u0192\u02c6\u02dc\u2013\u2014\u2018-\u201e\u2020-\u2022\u2026"
    "\u2030\u2039\u203a\u20ac\u2122]{1,3}"
)

# A no-break space is the one continuation character text extraction rewrites,
# turning it into a plain space and breaking the sequence it belonged to.
NBSP_EATEN = re.compile("([ÂÃ]) ")

MOJIBAKE_MARKERS = ("Â", "Ã", "â")


def _decode_run(match: re.Match[str]) -> str:
    try:
        return match.group(0).encode("cp1252").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return match.group(0)


def repair_encoding(text: str) -> str:
    """Undo UTF-8-read-as-Windows-1252 mangling, a line at a time.

    Italian merchant names are what make this necessary: "Il Caffè
    all'Università" arrives as "Il CaffÃ¨ all'UniversitÃ ", and the euro signs
    on the same line arrive as "â‚¬". Left alone the amount never matches and
    the whole row is lost.

    The clean round-trip over the whole line is tried first and handles almost
    everything. When one character defeats it, the mangled runs are decoded
    individually instead, so a single unrecoverable character costs only itself.
    """
    if not any(marker in text for marker in MOJIBAKE_MARKERS):
        return text

    text = NBSP_EATEN.sub("\\1\xa0", text)
    try:
        return text.encode("cp1252").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return MOJIBAKE_RUN.sub(_decode_run, text)


def _parse_date(token: str) -> date | None:
    try:
        return datetime.strptime(token, "%d-%b-%y").date()
    except ValueError:
        return None


def _clean_description(text: str) -> str:
    description = text.strip()
    for row_type in ROW_TYPES:
        if description.endswith(row_type):
            description = description[: -len(row_type)].strip()
            break
    return " ".join(description.split())


def extract_lines(content: bytes) -> Iterable[str]:
    try:
        from pypdf import PdfReader
    except ImportError as exc:  # pragma: no cover - dependency is declared
        raise ProviderError("PDF support needs the pypdf package installed.") from exc

    try:
        reader = PdfReader(io.BytesIO(content))
    except Exception as exc:  # noqa: BLE001 - any malformed file lands here
        raise ProviderError(f"That file could not be read as a PDF: {exc}") from exc

    if reader.is_encrypted:
        try:
            reader.decrypt("")
        except Exception as exc:  # noqa: BLE001
            raise ProviderError(
                "That PDF is password-protected. Remove the password and try again."
            ) from exc

    for page in reader.pages:
        try:
            text = page.extract_text() or ""
        except Exception:  # noqa: BLE001 - skip a page rather than fail the import
            continue
        for line in text.splitlines():
            yield repair_encoding(line).strip()


def parse_pdf(content: bytes, default_currency: str = "EUR") -> list[NormalisedTransaction]:
    """Read every transaction row out of a Revolut PDF statement."""
    return parse_lines(extract_lines(content), default_currency=default_currency)


def parse_lines(
    lines: Iterable[str], default_currency: str = "EUR"
) -> list[NormalisedTransaction]:
    """Turn extracted text lines into transactions.

    Split out from the PDF reading so the table logic can be exercised without
    a binary fixture.
    """
    transactions: list[NormalisedTransaction] = []
    last_date: date | None = None

    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith(SKIP_PREFIXES):
            continue

        match = ROW.match(line)
        if not match:
            continue

        rest = match.group("rest")
        amounts = list(MONEY.finditer(rest))
        if not amounts:
            continue

        token = match.group("date")
        date_was_unreadable = token.startswith("#")
        booked_at = last_date if date_was_unreadable else _parse_date(token)
        if booked_at is None:
            continue  # nothing sensible to date it by
        if not date_was_unreadable:
            last_date = booked_at

        amount_match = amounts[0]
        currency = SYMBOL_CURRENCY.get(amount_match.group("symbol"), default_currency)
        value = amount_match.group("value").replace(",", "")
        signed = f"-{value}" if amount_match.group("sign") == "-" else value

        description = _clean_description(rest[: amount_match.start()])
        if not description:
            continue

        transactions.append(
            NormalisedTransaction(
                external_id=None,  # filled in below, once repeats can be counted
                booked_at=booked_at,
                value_date=booked_at,
                amount_minor=to_minor(signed, currency),
                currency=currency,
                description=description[:400],
                merchant=description[:200],
                state="completed",
                raw={"date_unreadable": date_was_unreadable},
            )
        )

    if not transactions:
        raise ProviderError(
            "No transactions could be found in that PDF. If it isn't a Revolut "
            "statement, export a CSV from your bank and import that instead."
        )

    assign_occurrence_ids(transactions, "pdf")
    return transactions


def count_undated(transactions: list[NormalisedTransaction]) -> int:
    """How many rows had a date the PDF didn't render legibly."""
    return sum(1 for txn in transactions if txn.raw.get("date_unreadable"))
