"""Revolut Business API provider.

Auth is OAuth 2.0 authorisation-code with a *private key JWT* client assertion:
you upload an X.509 certificate in the Revolut business portal and sign
assertions with the matching private key. Access tokens last ~40 minutes;
the refresh token lives until the certificate expires (90 days by default),
at which point the user has to re-authorise.

Docs: https://developer.revolut.com/docs/business/
"""
from __future__ import annotations

import time
from datetime import date, datetime, timedelta, timezone
from typing import Any
from urllib.parse import urlencode, urlparse

import httpx
import jwt

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

ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
_PAGE_SIZE = 1000
_TOKEN_SKEW_SECONDS = 60


def build_authorize_url(settings: Settings, state: str) -> str:
    """URL the user visits to grant this app read access to their business account."""
    if not settings.revolut_client_id:
        raise ProviderError("REVOLUT_CLIENT_ID is not configured.")
    if not settings.revolut_private_key_path:
        # Fail here rather than after the user has authorised: without the key we
        # could never exchange the code for a token.
        raise ProviderError("REVOLUT_PRIVATE_KEY_PATH is not configured.")
    query = urlencode(
        {
            "client_id": settings.revolut_client_id,
            "redirect_uri": settings.effective_revolut_redirect_uri,
            "response_type": "code",
            "scope": "READ",
            "state": state,
        }
    )
    return f"{settings.revolut_authorize_base}?{query}"


def _client_assertion(settings: Settings) -> str:
    """Sign the JWT that authenticates this client to Revolut's token endpoint."""
    if not settings.revolut_private_key_path:
        raise ProviderError("REVOLUT_PRIVATE_KEY_PATH is not configured.")
    try:
        with open(settings.revolut_private_key_path, "rb") as handle:
            private_key = handle.read()
    except OSError as exc:
        raise ProviderError(f"Cannot read Revolut private key: {exc}") from exc

    issuer = urlparse(settings.effective_revolut_redirect_uri).hostname
    if not issuer:
        raise ProviderError("REVOLUT_REDIRECT_URI must be an absolute URL.")

    now = int(time.time())
    return jwt.encode(
        {
            "iss": issuer,
            "sub": settings.revolut_client_id,
            "aud": "https://revolut.com",
            "iat": now,
            "exp": now + 3600,
        },
        private_key,
        algorithm="RS256",
    )


async def exchange_code(settings: Settings, code: str) -> dict[str, Any]:
    """Trade the authorisation code for access + refresh tokens."""
    return await _token_request(
        settings,
        {
            "grant_type": "authorization_code",
            "code": code,
        },
    )


async def _token_request(settings: Settings, extra: dict[str, str]) -> dict[str, Any]:
    payload = {
        "client_id": settings.revolut_client_id,
        "client_assertion_type": ASSERTION_TYPE,
        "client_assertion": _client_assertion(settings),
        **extra,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            f"{settings.revolut_api_base}/auth/token",
            data=payload,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
    if response.status_code in (400, 401):
        raise ConsentExpired(f"Revolut rejected the token request: {response.text}")
    if response.status_code >= 400:
        raise ProviderError(f"Revolut token endpoint returned {response.status_code}: {response.text}")

    data = response.json()
    expires_in = int(data.get("expires_in", 2400))
    data["access_token_expires_at"] = (
        datetime.now(timezone.utc) + timedelta(seconds=expires_in)
    ).isoformat()
    return data


class RevolutBusinessProvider(BankProvider):
    key = "revolut_business"
    display_name = "Revolut Business"

    def __init__(
        self,
        settings: Settings,
        credentials: dict[str, Any],
        on_credentials_refreshed: CredentialSink | None = None,
    ) -> None:
        self.settings = settings
        self.credentials = dict(credentials)
        self._sink = on_credentials_refreshed

    # -- auth ------------------------------------------------------------
    def _access_token_valid(self) -> bool:
        token = self.credentials.get("access_token")
        expiry = self.credentials.get("access_token_expires_at")
        if not token or not expiry:
            return False
        try:
            expires_at = datetime.fromisoformat(expiry)
        except ValueError:
            return False
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        return expires_at - timedelta(seconds=_TOKEN_SKEW_SECONDS) > datetime.now(timezone.utc)

    async def _ensure_token(self) -> str:
        if self._access_token_valid():
            return self.credentials["access_token"]

        refresh_token = self.credentials.get("refresh_token")
        if not refresh_token:
            raise ConsentExpired("No refresh token stored — reconnect the Revolut account.")

        refreshed = await _token_request(
            self.settings, {"grant_type": "refresh_token", "refresh_token": refresh_token}
        )
        # Revolut only returns a new refresh token on re-authorisation, so keep ours.
        refreshed.setdefault("refresh_token", refresh_token)
        self.credentials.update(refreshed)
        if self._sink:
            self._sink(self.credentials)
        return self.credentials["access_token"]

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        token = await self._ensure_token()
        async with httpx.AsyncClient(timeout=45) as client:
            response = await client.get(
                f"{self.settings.revolut_api_base}{path}",
                params=params,
                headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
            )
        if response.status_code == 401:
            raise ConsentExpired("Revolut returned 401 — the authorisation is no longer valid.")
        if response.status_code == 429:
            raise ProviderError("Revolut rate limit hit; try the sync again shortly.")
        if response.status_code >= 400:
            raise ProviderError(f"Revolut GET {path} failed ({response.status_code}): {response.text}")
        return response.json()

    # -- data ------------------------------------------------------------
    async def fetch_accounts(self) -> list[NormalisedAccount]:
        payload = await self._get("/accounts")
        accounts: list[NormalisedAccount] = []
        for item in payload or []:
            currency = item.get("currency", self.settings.default_currency)
            accounts.append(
                NormalisedAccount(
                    external_id=item["id"],
                    name=item.get("name") or f"Revolut {currency}",
                    currency=currency,
                    balance_minor=to_minor(item.get("balance", 0), currency),
                    account_type=item.get("state") and "business_current" or "business_current",
                    iban_last4=None,
                )
            )
        return accounts

    async def fetch_transactions(
        self, account_external_id: str, since: date, until: date | None = None
    ) -> list[NormalisedTransaction]:
        until = until or date.today()
        results: list[NormalisedTransaction] = []
        seen: set[str] = set()
        cursor = datetime.combine(until, datetime.max.time()).replace(tzinfo=timezone.utc)
        floor = datetime.combine(since, datetime.min.time()).replace(tzinfo=timezone.utc)

        # Revolut returns newest-first; walk backwards using `to` as a cursor.
        for _ in range(100):  # hard stop so a misbehaving cursor can't spin forever
            page = await self._get(
                "/transactions",
                {
                    "account": account_external_id,
                    "from": floor.date().isoformat(),
                    "to": cursor.date().isoformat(),
                    "count": _PAGE_SIZE,
                },
            )
            if not page:
                break

            oldest = cursor
            new_in_page = 0
            for item in page:
                if item.get("id") in seen:
                    continue
                seen.add(item.get("id"))
                new_in_page += 1
                created = _parse_dt(item.get("created_at"))
                if created and created < oldest:
                    oldest = created
                results.extend(_normalise(item, account_external_id))

            if len(page) < _PAGE_SIZE or new_in_page == 0 or oldest <= floor:
                break
            cursor = oldest - timedelta(seconds=1)

        return [txn for txn in results if txn.booked_at >= since]


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _normalise(item: dict[str, Any], account_external_id: str) -> list[NormalisedTransaction]:
    """One Revolut transaction can touch several accounts; keep only our legs."""
    state = item.get("state", "completed")
    if state in {"declined", "failed", "reverted"}:
        return []

    booked = _parse_dt(item.get("completed_at")) or _parse_dt(item.get("created_at"))
    if booked is None:
        return []

    merchant = (item.get("merchant") or {}).get("name")
    provider_category = (item.get("merchant") or {}).get("category_code")
    txn_type = item.get("type", "")

    out: list[NormalisedTransaction] = []
    for leg in item.get("legs", []):
        if leg.get("account_id") != account_external_id:
            continue
        currency = leg.get("currency", "GBP")
        counterparty = (leg.get("counterparty") or {}).get("account_id")
        description = leg.get("description") or merchant or txn_type.replace("_", " ").title()
        out.append(
            NormalisedTransaction(
                external_id=f"{item['id']}:{leg.get('leg_id', '0')}",
                booked_at=booked.date(),
                value_date=(_parse_dt(item.get("created_at")) or booked).date(),
                amount_minor=to_minor(leg.get("amount", 0), currency),
                currency=currency,
                description=description,
                merchant=merchant,
                counterparty=counterparty,
                reference=item.get("reference"),
                provider_category=provider_category or txn_type or None,
                state="pending" if state == "pending" else "completed",
                raw={"type": txn_type, "revolut_id": item.get("id")},
            )
        )
    return out
