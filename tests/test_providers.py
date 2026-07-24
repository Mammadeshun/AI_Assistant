"""Provider normalisation, driven by mocked HTTP responses."""
from __future__ import annotations

from datetime import date

import httpx
import pytest
import respx

from backend.app.config import get_settings
from backend.app.providers.base import ConsentExpired, ProviderError
from backend.app.providers.gocardless import API_BASE, GoCardlessProvider, _pick_balances
from backend.app.providers.revolut_business import RevolutBusinessProvider, _normalise


# --------------------------------------------------------------------------
# Revolut Business
# --------------------------------------------------------------------------
def test_revolut_normalise_keeps_only_our_leg():
    payload = {
        "id": "txn-1",
        "type": "transfer",
        "state": "completed",
        "created_at": "2025-03-01T10:00:00.000Z",
        "completed_at": "2025-03-01T10:00:05.000Z",
        "reference": "Rent",
        "legs": [
            {"leg_id": "a", "account_id": "acct-1", "amount": -500.0, "currency": "GBP",
             "description": "To landlord"},
            {"leg_id": "b", "account_id": "acct-2", "amount": 500.0, "currency": "GBP",
             "description": "From me"},
        ],
    }
    transactions = _normalise(payload, "acct-1")

    assert len(transactions) == 1
    assert transactions[0].amount_minor == -50_000
    assert transactions[0].external_id == "txn-1:a"
    assert transactions[0].booked_at == date(2025, 3, 1)
    assert transactions[0].reference == "Rent"


def test_revolut_normalise_drops_declined():
    payload = {
        "id": "txn-2", "state": "declined", "created_at": "2025-03-01T10:00:00Z",
        "legs": [{"leg_id": "a", "account_id": "acct-1", "amount": -1.0, "currency": "GBP"}],
    }
    assert _normalise(payload, "acct-1") == []


def test_revolut_normalise_uses_merchant_when_description_missing():
    payload = {
        "id": "txn-3", "type": "card_payment", "state": "completed",
        "created_at": "2025-03-02T10:00:00Z",
        "merchant": {"name": "Pret A Manger", "category_code": "5812"},
        "legs": [{"leg_id": "a", "account_id": "acct-1", "amount": -4.5, "currency": "GBP"}],
    }
    transaction = _normalise(payload, "acct-1")[0]
    assert transaction.description == "Pret A Manger"
    assert transaction.provider_category == "5812"


@respx.mock
async def test_revolut_fetch_accounts():
    settings = get_settings()
    respx.get(f"{settings.revolut_api_base}/accounts").mock(
        return_value=httpx.Response(
            200,
            json=[
                {"id": "acct-1", "name": "Main GBP", "balance": 1234.56, "currency": "GBP", "state": "active"},
                {"id": "acct-2", "name": "EUR pocket", "balance": 10.0, "currency": "EUR", "state": "active"},
            ],
        )
    )

    provider = RevolutBusinessProvider(
        settings, {"access_token": "tok", "access_token_expires_at": "2099-01-01T00:00:00+00:00"}
    )
    accounts = await provider.fetch_accounts()

    assert [a.external_id for a in accounts] == ["acct-1", "acct-2"]
    assert accounts[0].balance_minor == 123_456
    assert accounts[1].currency == "EUR"


@respx.mock
async def test_revolut_401_asks_for_reconnect():
    settings = get_settings()
    respx.get(f"{settings.revolut_api_base}/accounts").mock(return_value=httpx.Response(401, text="nope"))

    provider = RevolutBusinessProvider(
        settings, {"access_token": "tok", "access_token_expires_at": "2099-01-01T00:00:00+00:00"}
    )
    with pytest.raises(ConsentExpired):
        await provider.fetch_accounts()


@respx.mock
async def test_revolut_rate_limit_is_a_provider_error():
    settings = get_settings()
    respx.get(f"{settings.revolut_api_base}/accounts").mock(return_value=httpx.Response(429))

    provider = RevolutBusinessProvider(
        settings, {"access_token": "tok", "access_token_expires_at": "2099-01-01T00:00:00+00:00"}
    )
    with pytest.raises(ProviderError, match="rate limit"):
        await provider.fetch_accounts()


async def test_revolut_without_refresh_token_raises():
    provider = RevolutBusinessProvider(get_settings(), {})
    with pytest.raises(ConsentExpired, match="reconnect"):
        await provider._ensure_token()


# --------------------------------------------------------------------------
# GoCardless
# --------------------------------------------------------------------------
def test_balance_preference():
    balances = [
        {"balanceType": "interimAvailable", "balanceAmount": {"amount": "90.00", "currency": "GBP"}},
        {"balanceType": "closingBooked", "balanceAmount": {"amount": "100.00", "currency": "GBP"}},
    ]
    booked, available = _pick_balances(balances, "GBP")
    assert booked == 10_000
    assert available == 9_000


def test_balance_falls_back_to_first_entry():
    balances = [{"balanceType": "somethingElse", "balanceAmount": {"amount": "5.50", "currency": "GBP"}}]
    booked, available = _pick_balances(balances, "GBP")
    assert booked == 550
    assert available is None


@respx.mock
async def test_gocardless_fetch_transactions_splits_booked_and_pending():
    settings = get_settings()
    respx.post(f"{API_BASE}/token/new/").mock(
        return_value=httpx.Response(200, json={"access": "tok", "refresh": "r"})
    )
    respx.get(f"{API_BASE}/accounts/acct-1/transactions/").mock(
        return_value=httpx.Response(
            200,
            json={
                "transactions": {
                    "booked": [
                        {
                            "transactionId": "t1",
                            "bookingDate": "2025-03-01",
                            "valueDate": "2025-03-01",
                            "transactionAmount": {"amount": "-42.50", "currency": "GBP"},
                            "creditorName": "Tesco",
                            "remittanceInformationUnstructured": "TESCO STORES 3241",
                        }
                    ],
                    "pending": [
                        {
                            "transactionId": "t2",
                            "bookingDate": "2025-03-02",
                            "transactionAmount": {"amount": "-9.99", "currency": "GBP"},
                            "remittanceInformationUnstructuredArray": ["NETFLIX", "SUBSCRIPTION"],
                        }
                    ],
                }
            },
        )
    )

    provider = GoCardlessProvider(settings, {"requisition_id": "req-1"})
    transactions = await provider.fetch_transactions("acct-1", date(2025, 1, 1))

    assert len(transactions) == 2
    booked, pending = transactions
    assert booked.amount_minor == -4250
    assert booked.merchant == "Tesco"
    assert booked.state == "completed"
    assert pending.state == "pending"
    assert pending.description == "NETFLIX SUBSCRIPTION"


@respx.mock
async def test_gocardless_expired_requisition():
    settings = get_settings()
    respx.post(f"{API_BASE}/token/new/").mock(
        return_value=httpx.Response(200, json={"access": "tok"})
    )
    respx.get(f"{API_BASE}/requisitions/req-1/").mock(
        return_value=httpx.Response(200, json={"status": "EX", "accounts": []})
    )

    provider = GoCardlessProvider(settings, {"requisition_id": "req-1"})
    with pytest.raises(ConsentExpired):
        await provider.fetch_accounts()


@respx.mock
async def test_gocardless_rate_limit_message_is_actionable():
    settings = get_settings()
    respx.post(f"{API_BASE}/token/new/").mock(return_value=httpx.Response(200, json={"access": "tok"}))
    respx.get(f"{API_BASE}/requisitions/req-1/").mock(return_value=httpx.Response(429))

    provider = GoCardlessProvider(settings, {"requisition_id": "req-1"})
    with pytest.raises(ProviderError, match="rate limit"):
        await provider.fetch_accounts()
