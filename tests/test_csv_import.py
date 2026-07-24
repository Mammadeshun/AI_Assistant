"""CSV parsing across the shapes Revolut and other banks actually emit."""
from __future__ import annotations

from datetime import date

import pytest

from backend.app.providers.base import ProviderError
from backend.app.providers.csv_import import parse_csv

REVOLUT_EXPORT = """Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
CARD_PAYMENT,Current,2025-03-01 08:12:00,2025-03-01 09:00:00,Tesco Stores,-42.50,0.00,GBP,COMPLETED,957.50
TOPUP,Current,2025-03-02 10:00:00,2025-03-02 10:01:00,Payment from Employer,2000.00,0.00,GBP,COMPLETED,2957.50
CARD_PAYMENT,Current,2025-03-03 19:30:00,2025-03-03 19:31:00,Netflix,-10.99,0.00,GBP,COMPLETED,2946.51
CARD_PAYMENT,Current,2025-03-04 11:00:00,,Declined thing,-5.00,0.00,GBP,REVERTED,2946.51
"""

LEGACY_UK_EXPORT = """Date,Description,Paid Out,Paid In,Balance
01/03/2025,SAINSBURYS,"1,234.56",,500.00
02/03/2025,SALARY,,2500.00,3000.00
"""


def test_parses_revolut_export():
    transactions = parse_csv(REVOLUT_EXPORT)

    assert len(transactions) == 3  # the REVERTED row is dropped
    first = transactions[0]
    assert first.booked_at == date(2025, 3, 1)
    assert first.amount_minor == -4250
    assert first.currency == "GBP"
    assert first.description == "Tesco Stores"
    assert transactions[1].amount_minor == 200_000


def test_fee_is_deducted():
    csv_with_fee = REVOLUT_EXPORT.replace("-42.50,0.00", "-42.50,1.50")
    transactions = parse_csv(csv_with_fee)
    assert transactions[0].amount_minor == -4400  # -42.50 spent plus 1.50 fee


def test_parses_paid_in_out_columns():
    transactions = parse_csv(LEGACY_UK_EXPORT)

    assert len(transactions) == 2
    assert transactions[0].amount_minor == -123_456  # thousands separator handled
    assert transactions[0].booked_at == date(2025, 3, 1)
    assert transactions[1].amount_minor == 250_000


def test_semicolon_delimiter_and_currency_symbols():
    content = "Date;Description;Amount\n2025-01-15;Café;-£3.20\n"
    transactions = parse_csv(content)
    assert transactions[0].amount_minor == -320


def test_parenthesised_negatives():
    content = "Date,Description,Amount\n2025-01-15,Refund,(25.00)\n"
    assert parse_csv(content)[0].amount_minor == -2500


def test_rejects_file_without_amount_column():
    with pytest.raises(ProviderError, match="amount column"):
        parse_csv("Date,Description\n2025-01-01,Nothing\n")


def test_rejects_empty_file():
    with pytest.raises(ProviderError):
        parse_csv("")


def test_skips_unparseable_rows_rather_than_failing():
    content = REVOLUT_EXPORT + "TOTALS,,,,Summary row,,,,,\n"
    assert len(parse_csv(content)) == 3
