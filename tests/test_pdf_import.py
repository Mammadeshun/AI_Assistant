"""PDF statement parsing, against the shapes a real Revolut statement contains.

The lines below are taken from an actual statement's text layer, mojibake and
all, because those quirks are the whole reason this parser exists.
"""
from __future__ import annotations

from datetime import date

import pytest

from backend.app.providers.base import ProviderError
from backend.app.providers.pdf_import import (
    count_undated,
    parse_lines,
    parse_pdf,
    repair_encoding,
)

STATEMENT = [
    "Date Description Money out Money in Balance",
    "27-Nov-23 Payment from EmployerTransfer €1,500.00 €1,500.00",
    "28-Nov-23 Tesco StoresMerchant -€42.50 €1,457.50",
    "29-Nov-23 Card Delivery FeeFees -€4.99 €1,452.51",
    "Total €1,500.00 €47.49",
]


def test_reads_a_statement_table():
    transactions = parse_lines(STATEMENT)

    assert len(transactions) == 3
    first = transactions[0]
    assert first.booked_at == date(2023, 11, 27)
    assert first.amount_minor == 150_000
    assert first.currency == "EUR"
    assert first.description == "Payment from Employer"
    assert transactions[1].amount_minor == -4250


def test_transaction_type_is_stripped_from_the_description():
    # Revolut runs the type onto the end of the description with no space, and
    # "Fees" has to win over "Fee" or the description keeps a stray "s".
    transactions = parse_lines(STATEMENT)
    assert transactions[2].description == "Card Delivery Fee"


def test_summary_and_header_rows_are_ignored():
    assert all("Total" not in txn.description for txn in parse_lines(STATEMENT))


def test_hash_dates_are_carried_forward_and_flagged():
    lines = STATEMENT + ["######## SpotifyMerchant -€10.99 €1,441.52"]
    transactions = parse_lines(lines)

    unreadable = transactions[-1]
    assert unreadable.booked_at == date(2023, 11, 29)  # dated from the row above
    assert unreadable.raw["date_unreadable"] is True
    assert count_undated(transactions) == 1


def test_a_hash_date_before_any_real_date_is_dropped():
    # Nothing to carry forward from, so the row cannot be dated at all. Here it
    # is the only row, which leaves nothing to import.
    with pytest.raises(ProviderError, match="No transactions"):
        parse_lines(["######## SpotifyMerchant -€10.99 €1.00"])

    # With a dated row after it, the undatable one is dropped and the rest import.
    transactions = parse_lines(
        [
            "######## SpotifyMerchant -€10.99 €1.00",
            "01-Dec-23 Tesco StoresMerchant -€5.00 €1.00",
        ]
    )
    assert [t.description for t in transactions] == ["Tesco Stores"]


def test_amount_is_the_first_money_column_not_the_balance():
    # Amount and balance frequently run together with no separating space.
    transactions = parse_lines(["01-Dec-23 SpotifyMerchant -€10.99€1,441.52€0.00"])
    assert transactions[0].amount_minor == -1099


def test_currency_comes_from_the_symbol():
    transactions = parse_lines(["01-Dec-23 Amazon UKMerchant -£12.00 £30.00"])
    assert transactions[0].currency == "GBP"
    assert transactions[0].amount_minor == -1200


def test_identical_rows_on_one_day_are_kept_apart():
    # Two identical coffees in one day are two transactions, not one.
    lines = [
        "05-Dec-23 Bar CentraleMerchant -€1.50 €10.00",
        "05-Dec-23 Bar CentraleMerchant -€1.50 €8.50",
    ]
    transactions = parse_lines(lines)

    assert len({txn.dedupe_key() for txn in transactions}) == 2
    # ...and re-reading the same statement recognises them rather than doubling.
    assert [t.dedupe_key() for t in parse_lines(lines)] == [
        t.dedupe_key() for t in transactions
    ]


def test_mojibake_currency_symbols_are_repaired():
    line = "09-Jan-25 Il CaffÃ¨ all'UniversitÃ Merchant -â‚¬5.50 â‚¬591.73"
    transactions = parse_lines([repair_encoding(line)])

    assert transactions[0].amount_minor == -550
    assert transactions[0].currency == "EUR"


def test_accents_survive_the_repair():
    # A trailing "à" reaches us as "Ã" plus a space, the no-break space having
    # been flattened during extraction; it still has to come back as "à".
    assert repair_encoding("Il CaffÃ¨ all'UniversitÃ ") == "Il Caffè all'Università"
    assert repair_encoding("TigotÃ  Merchant") == "Tigotà Merchant"


def test_repair_survives_a_character_it_cannot_decode():
    # One unrecoverable character must not cost the rest of the line — the euro
    # sign still has to come back.
    line = "Bar \udcff â‚¬5.50"
    assert "€5.50" in repair_encoding(line)


def test_clean_text_is_left_alone():
    assert repair_encoding("Tesco Stores €5.00") == "Tesco Stores €5.00"


def test_a_file_that_is_not_a_pdf_is_rejected():
    with pytest.raises(ProviderError, match="could not be read as a PDF"):
        parse_pdf(b"just some text, not a PDF at all")


def test_a_pdf_with_no_transactions_says_so():
    with pytest.raises(ProviderError, match="No transactions"):
        parse_lines(["Statement for account ending 1234", "Page 1 of 63"])
