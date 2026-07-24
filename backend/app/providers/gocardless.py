"""GoCardless Bank Account Data (ex-Nordigen) provider.

This is the route for a *personal* Revolut account: Revolut publishes no public
API for retail customers, but it is a regulated bank under UK/EU Open Banking,
so a licensed AISP aggregator can read it with your consent. GoCardless Bank
Account Data has a free tier and covers Revolut in the UK (REVOLUT_REVOGB21)
and across the EEA.

Flow: create an end-user agreement -> create a requisition -> send the user to
`link` -> they authorise in the Revolut app -> we come back and read accounts.

Consent lasts up to 90 days (regulatory maximum), then must be renewed.
Note the production rate limit: 4 transaction/balance calls per account per day.

Docs: https://developer.gocardless.com/bank-account-data/
"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from typing import Any

import httpx

from ..config import Settings
from .base import (
    BankProvider,
    ConsentExpired,
    CredentialSink,
    NormalisedAccount,
    NormalisedTransaction,
    ProviderError,
    to_minor,
)

API_BASE = "https://bankaccountdata.gocardless.com/api/v2"
MAX_CONSENT_DAYS = 90

# Revolut's institution ids per market. UK is the common case.
REVOLUT_INSTITUTIONS = {
    "GB": "REVOLUT_REVOGB21",
    "IE": "REVOLUT_REVOLT21",
    "LT": "REVOLUT_REVOLT21",
}


def revolut_institution_for(country: str) -> str:
    return REVOLUT_INSTITUTIONS.get(country.upper(), "REVOLUT_REVOLT21")


async def _access_token(settings: Settings) -> str:
    if not settings.gocardless_secret_id or not settings.gocardless_secret_key:
        raise ProviderError("GOCARDLESS_SECRET_ID / GOCARDLESS_SECRET_KEY are not configured.")
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            f"{API_BASE}/token/new/",
            json={
                "secret_id": settings.gocardless_secret_id,
                "secret_key": settings.gocardless_secret_key,
            },
        )
    if response.status_code >= 400:
        raise ProviderError(f"GoCardless auth failed ({response.status_code}): {response.text}")
    return response.json()["access"]


async def _request(
    settings: Settings, method: str, path: str, *, json: dict | None = None, params: dict | None = None
) -> Any:
    token = await _access_token(settings)
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.request(
            method,
            f"{API_BASE}{path}",
            json=json,
            params=params,
            headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        )
    if response.status_code == 401:
        raise ConsentExpired("GoCardless returned 401 — credentials or consent are invalid.")
    if response.status_code == 429:
        raise ProviderError(
            "GoCardless rate limit reached (4 calls per account per day in production). "
            "Try again tomorrow or use cached data."
        )
    if response.status_code >= 400:
        detail = response.text
        if "expired" in detail.lower() or response.status_code == 403:
            raise ConsentExpired(f"GoCardless consent problem: {detail}")
        raise ProviderError(f"GoCardless {method} {path} failed ({response.status_code}): {detail}")
    if response.status_code == 204 or not response.content:
        return None
    return response.json()


async def list_institutions(settings: Settings, country: str) -> list[dict[str, Any]]:
    return await _request(settings, "GET", "/institutions/", params={"country": country.lower()})


async def create_link(
    settings: Settings, institution_id: str, reference: str, days: int = MAX_CONSENT_DAYS
) -> dict[str, Any]:
    """Create the agreement + requisition and return the hosted authorisation link."""
    agreement = await _request(
        settings,
        "POST",
        "/agreements/enduser/",
        json={
            "institution_id": institution_id,
            "max_historical_days": min(days, MAX_CONSENT_DAYS),
            "access_valid_for_days": min(days, MAX_CONSENT_DAYS),
            "access_scope": ["balances", "details", "transactions"],
        },
    )
    requisition = await _request(
        settings,
        "POST",
        "/requisitions/",
        json={
            "redirect": settings.effective_gocardless_redirect_uri,
            "institution_id": institution_id,
            "reference": reference,
            "agreement": agreement["id"],
            "user_language": "EN",
        },
    )
    return {
        "requisition_id": requisition["id"],
        "agreement_id": agreement["id"],
        "link": requisition["link"],
        "institution_id": institution_id,
        "consent_expires_at": (
            datetime.now(timezone.utc) + timedelta(days=min(days, MAX_CONSENT_DAYS))
        ).isoformat(),
    }


async def get_requisition(settings: Settings, requisition_id: str) -> dict[str, Any]:
    return await _request(settings, "GET", f"/requisitions/{requisition_id}/")


async def delete_requisition(settings: Settings, requisition_id: str) -> None:
    await _request(settings, "DELETE", f"/requisitions/{requisition_id}/")


class GoCardlessProvider(BankProvider):
    key = "gocardless"
    display_name = "Revolut (Open Banking)"

    def __init__(
        self,
        settings: Settings,
        credentials: dict[str, Any],
        on_credentials_refreshed: CredentialSink | None = None,
    ) -> None:
        self.settings = settings
        self.credentials = dict(credentials)
        self._sink = on_credentials_refreshed

    async def fetch_accounts(self) -> list[NormalisedAccount]:
        requisition_id = self.credentials.get("requisition_id")
        if not requisition_id:
            raise ProviderError("Connection is missing its GoCardless requisition id.")

        requisition = await get_requisition(self.settings, requisition_id)
        status = requisition.get("status")
        if status in {"EX", "SU", "RJ"}:  # expired / suspended / rejected
            raise ConsentExpired(f"GoCardless requisition status is {status} — reconnect the account.")
        if not requisition.get("accounts"):
            raise ProviderError(
                "No accounts returned yet — finish the Revolut authorisation in the browser first."
            )

        accounts: list[NormalisedAccount] = []
        for account_id in requisition["accounts"]:
            metadata = await _request(self.settings, "GET", f"/accounts/{account_id}/")
            details = (
                await _request(self.settings, "GET", f"/accounts/{account_id}/details/")
            ) or {}
            detail = details.get("account", {})
            balances = (
                await _request(self.settings, "GET", f"/accounts/{account_id}/balances/")
            ) or {}

            currency = detail.get("currency") or metadata.get("currency") or self.settings.default_currency
            booked, available = _pick_balances(balances.get("balances", []), currency)
            iban = metadata.get("iban") or detail.get("iban")

            accounts.append(
                NormalisedAccount(
                    external_id=account_id,
                    name=detail.get("name")
                    or detail.get("product")
                    or metadata.get("owner_name")
                    or f"Revolut {currency}",
                    currency=currency,
                    balance_minor=booked,
                    available_minor=available,
                    account_type=detail.get("cashAccountType") or "personal_current",
                    iban_last4=iban[-4:] if iban else None,
                )
            )
        return accounts

    async def fetch_transactions(
        self, account_external_id: str, since: date, until: date | None = None
    ) -> list[NormalisedTransaction]:
        until = until or date.today()
        payload = await _request(
            self.settings,
            "GET",
            f"/accounts/{account_external_id}/transactions/",
            params={"date_from": since.isoformat(), "date_to": until.isoformat()},
        )
        buckets = (payload or {}).get("transactions", {})
        out: list[NormalisedTransaction] = []
        for item in buckets.get("booked", []):
            normalised = _normalise(item, state="completed")
            if normalised:
                out.append(normalised)
        for item in buckets.get("pending", []):
            normalised = _normalise(item, state="pending")
            if normalised:
                out.append(normalised)
        return out


def _pick_balances(balances: list[dict[str, Any]], currency: str) -> tuple[int, int | None]:
    """Berlin Group exposes several balance types; prefer closingBooked / interimAvailable."""
    booked_minor = 0
    available_minor: int | None = None
    preference = ["closingBooked", "interimBooked", "expected", "interimAvailable", "forwardAvailable"]

    by_type = {b.get("balanceType"): b for b in balances if b.get("balanceAmount")}
    for balance_type in preference:
        entry = by_type.get(balance_type)
        if entry:
            amount = entry["balanceAmount"]
            booked_minor = to_minor(amount.get("amount", 0), amount.get("currency", currency))
            break
    else:
        if balances:
            amount = balances[0].get("balanceAmount", {})
            booked_minor = to_minor(amount.get("amount", 0), amount.get("currency", currency))

    for balance_type in ("interimAvailable", "forwardAvailable"):
        entry = by_type.get(balance_type)
        if entry:
            amount = entry["balanceAmount"]
            available_minor = to_minor(amount.get("amount", 0), amount.get("currency", currency))
            break

    return booked_minor, available_minor


def _normalise(item: dict[str, Any], state: str) -> NormalisedTransaction | None:
    amount = item.get("transactionAmount") or {}
    currency = amount.get("currency", "GBP")
    booked_raw = item.get("bookingDate") or item.get("valueDate")
    if not booked_raw:
        return None
    try:
        booked_at = date.fromisoformat(booked_raw[:10])
    except ValueError:
        return None

    remittance = item.get("remittanceInformationUnstructured")
    if not remittance:
        parts = item.get("remittanceInformationUnstructuredArray") or []
        remittance = " ".join(parts) if parts else None

    amount_minor = to_minor(amount.get("amount", 0), currency)
    counterparty = item.get("creditorName") if amount_minor < 0 else item.get("debtorName")
    description = remittance or counterparty or item.get("additionalInformation") or "Transaction"

    value_date = None
    if item.get("valueDate"):
        try:
            value_date = date.fromisoformat(item["valueDate"][:10])
        except ValueError:
            value_date = None

    return NormalisedTransaction(
        external_id=item.get("transactionId") or item.get("internalTransactionId"),
        booked_at=booked_at,
        value_date=value_date,
        amount_minor=amount_minor,
        currency=currency,
        description=description.strip(),
        merchant=counterparty,
        counterparty=counterparty,
        reference=item.get("endToEndId") or item.get("checkId"),
        provider_category=item.get("merchantCategoryCode")
        or (item.get("proprietaryBankTransactionCode") or None),
        state=state,
        raw={},
    )
