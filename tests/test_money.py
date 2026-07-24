"""Money conversion must never lose or invent a penny."""
from __future__ import annotations

import pytest

from backend.app.providers.base import NormalisedTransaction, from_minor, to_minor
from datetime import date


@pytest.mark.parametrize(
    "amount,currency,expected",
    [
        (12.34, "GBP", 1234),
        ("12.345", "GBP", 1235),   # rounds half up, not banker's rounding
        (-9.99, "EUR", -999),
        (0.1 + 0.2, "GBP", 30),    # the classic float trap
        (1500, "JPY", 1500),       # zero-decimal currency
        (1.5, "JPY", 2),
        (10.5555, "KWD", 10556),   # three-decimal currency
        (0, "GBP", 0),
    ],
)
def test_to_minor(amount, currency, expected):
    assert to_minor(amount, currency) == expected


def test_round_trip():
    assert from_minor(to_minor(87.65, "GBP"), "GBP") == 87.65
    assert from_minor(to_minor(1200, "JPY"), "JPY") == 1200


def test_dedupe_key_prefers_external_id():
    base = dict(booked_at=date(2025, 1, 5), amount_minor=-500, currency="GBP", description="Coffee")
    one = NormalisedTransaction(external_id="abc", **base)
    two = NormalisedTransaction(external_id="abc", **{**base, "description": "Different text"})
    assert one.dedupe_key() == two.dedupe_key()


def test_dedupe_key_falls_back_to_content():
    base = dict(booked_at=date(2025, 1, 5), amount_minor=-500, currency="GBP")
    one = NormalisedTransaction(external_id=None, description="Costa  Coffee", **base)
    two = NormalisedTransaction(external_id=None, description="costa coffee", **base)
    three = NormalisedTransaction(external_id=None, description="Costa Coffee", **{**base, "amount_minor": -600})

    assert one.dedupe_key() == two.dedupe_key()  # whitespace/case insensitive
    assert one.dedupe_key() != three.dedupe_key()  # different amount is a different transaction
