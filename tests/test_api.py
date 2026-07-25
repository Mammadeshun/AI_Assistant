"""End-to-end API behaviour, including the security boundaries."""
from __future__ import annotations

import io

from backend.app.config import get_settings
from backend.app.security import CredentialCipher, hash_password, verify_password


# --- auth -----------------------------------------------------------------
def test_health_is_public(client):
    assert client.get("/api/health").json()["status"] == "ok"


def test_endpoints_require_a_session(client):
    for path in ["/api/accounts", "/api/transactions", "/api/analytics/dashboard", "/api/connections"]:
        assert client.get(path).status_code == 401, path


def test_register_then_me(client):
    response = client.post(
        "/api/auth/register",
        json={"email": "Me@Example.com", "password": "a-long-enough-password", "base_currency": "gbp"},
    )
    assert response.status_code == 201
    assert response.json()["email"] == "me@example.com"
    assert response.json()["base_currency"] == "GBP"
    assert client.get("/api/auth/me").status_code == 200


def test_second_registration_is_refused(authed_client):
    response = authed_client.post(
        "/api/auth/register", json={"email": "other@example.com", "password": "another-password"}
    )
    assert response.status_code == 403


def test_short_passwords_are_rejected(client):
    response = client.post("/api/auth/register", json={"email": "a@b.com", "password": "short"})
    assert response.status_code == 422


def test_wrong_password_fails(authed_client):
    authed_client.post("/api/auth/logout")
    response = authed_client.post(
        "/api/auth/login", json={"email": "me@example.com", "password": "wrong-password-here"}
    )
    assert response.status_code == 401


def test_logout_clears_the_session(authed_client):
    assert authed_client.post("/api/auth/logout").status_code == 204
    assert authed_client.get("/api/auth/me").status_code == 401


def test_password_hashing_round_trip():
    stored = hash_password("correct horse battery staple")
    assert stored.startswith("pbkdf2_sha256$")
    assert verify_password("correct horse battery staple", stored)
    assert not verify_password("wrong", stored)
    assert not verify_password("x", "garbage")


def test_credentials_are_encrypted_at_rest():
    cipher = CredentialCipher()
    blob = cipher.encrypt({"access_token": "super-secret"})
    assert "super-secret" not in blob
    assert cipher.decrypt(blob) == {"access_token": "super-secret"}
    assert cipher.decrypt(None) == {}


# --- accounts & imports ---------------------------------------------------
def test_manual_account_and_csv_import(authed_client):
    account = authed_client.post(
        "/api/accounts", json={"name": "Revolut GBP", "currency": "GBP", "opening_balance_minor": 0}
    ).json()

    csv_content = (
        "Type,Started Date,Completed Date,Description,Amount,Fee,Currency,State\n"
        "CARD_PAYMENT,2025-03-01 08:00:00,2025-03-01 09:00:00,Tesco Stores,-42.50,0.00,GBP,COMPLETED\n"
        "TOPUP,2025-03-02 08:00:00,2025-03-02 09:00:00,Salary,2000.00,0.00,GBP,COMPLETED\n"
    )
    files = {"file": ("statement.csv", io.BytesIO(csv_content.encode()), "text/csv")}
    response = authed_client.post(f"/api/accounts/{account['id']}/import", files=files)

    assert response.status_code == 200
    assert response.json() == {"account_id": account["id"], "parsed": 2, "added": 2, "updated": 0}

    # Re-importing the same file must not duplicate anything.
    files = {"file": ("statement.csv", io.BytesIO(csv_content.encode()), "text/csv")}
    again = authed_client.post(f"/api/accounts/{account['id']}/import", files=files).json()
    assert again["added"] == 0

    transactions = authed_client.get("/api/transactions").json()
    assert transactions["total"] == 2


def test_import_recalculates_balance_when_asked(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    csv_content = "Date,Description,Amount\n2025-03-01,A,-10.00\n2025-03-02,B,25.00\n"
    files = {"file": ("s.csv", io.BytesIO(csv_content.encode()), "text/csv")}

    authed_client.post(
        f"/api/accounts/{account['id']}/import", files=files, data={"recalculate_balance": "true"}
    )
    accounts = authed_client.get("/api/accounts").json()
    assert accounts[0]["balance_minor"] == 1500


def test_import_rejects_a_nonsense_file(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    files = {"file": ("s.csv", io.BytesIO(b"not,a,statement\n1,2,3\n"), "text/csv")}
    response = authed_client.post(f"/api/accounts/{account['id']}/import", files=files)
    assert response.status_code == 422


# --- transactions ---------------------------------------------------------
def test_manual_transaction_and_filters(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    authed_client.post(
        "/api/transactions",
        json={
            "account_id": account["id"],
            "booked_at": "2025-03-01",
            "amount_minor": -1250,
            "description": "Pret A Manger",
        },
    ).raise_for_status()

    assert authed_client.get("/api/transactions?direction=out").json()["total"] == 1
    assert authed_client.get("/api/transactions?direction=in").json()["total"] == 0
    assert authed_client.get("/api/transactions?search=pret").json()["total"] == 1
    assert authed_client.get("/api/transactions?search=nothing").json()["total"] == 0


def test_manual_transaction_is_auto_categorised(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    created = authed_client.post(
        "/api/transactions",
        json={
            "account_id": account["id"],
            "booked_at": "2025-03-01",
            "amount_minor": -4250,
            "description": "TESCO STORES",
        },
    ).json()

    categories = {c["id"]: c["name"] for c in authed_client.get("/api/categories").json()}
    assert categories[created["category_id"]] == "Groceries"


def test_hand_set_category_survives_recategorisation(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    created = authed_client.post(
        "/api/transactions",
        json={"account_id": account["id"], "booked_at": "2025-03-01",
              "amount_minor": -4250, "description": "TESCO STORES"},
    ).json()

    categories = {c["name"]: c["id"] for c in authed_client.get("/api/categories").json()}
    authed_client.patch(
        f"/api/transactions/{created['id']}", json={"category_id": categories["Travel"]}
    ).raise_for_status()

    authed_client.post("/api/transactions/recategorise").raise_for_status()

    after = authed_client.get("/api/transactions").json()["items"][0]
    assert after["category_id"] == categories["Travel"]


def test_export_returns_csv(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    authed_client.post(
        "/api/transactions",
        json={"account_id": account["id"], "booked_at": "2025-03-01",
              "amount_minor": -4250, "description": "Tesco"},
    )
    response = authed_client.get("/api/transactions/export")
    assert response.status_code == 200
    assert "text/csv" in response.headers["content-type"]
    assert "Tesco" in response.text
    assert "-42.50" in response.text


def test_unknown_category_is_rejected(authed_client):
    account = authed_client.post("/api/accounts", json={"name": "Test", "currency": "GBP"}).json()
    created = authed_client.post(
        "/api/transactions",
        json={"account_id": account["id"], "booked_at": "2025-03-01",
              "amount_minor": -100, "description": "x"},
    ).json()
    assert authed_client.patch(
        f"/api/transactions/{created['id']}", json={"category_id": 999_999}
    ).status_code == 400


# --- categories, rules, budgets ------------------------------------------
def test_default_categories_are_seeded(authed_client):
    names = {c["name"] for c in authed_client.get("/api/categories").json()}
    assert {"Groceries", "Salary", "Transfers", "Uncategorised"} <= names


def test_system_categories_cannot_be_deleted(authed_client):
    groceries = next(c for c in authed_client.get("/api/categories").json() if c["name"] == "Groceries")
    assert authed_client.delete(f"/api/categories/{groceries['id']}").status_code == 400


def test_custom_category_lifecycle(authed_client):
    created = authed_client.post("/api/categories", json={"name": "Hobbies", "kind": "expense"})
    assert created.status_code == 201
    duplicate = authed_client.post("/api/categories", json={"name": "Hobbies", "kind": "expense"})
    assert duplicate.status_code == 409
    assert authed_client.delete(f"/api/categories/{created.json()['id']}").status_code == 204


def test_invalid_regex_rule_is_rejected(authed_client):
    response = authed_client.post(
        "/api/rules",
        json={"name": "broken", "field": "description", "match_type": "regex", "pattern": "([bad"},
    )
    assert response.status_code == 422


def test_budget_upsert_replaces_rather_than_duplicates(authed_client):
    groceries = next(c for c in authed_client.get("/api/categories").json() if c["name"] == "Groceries")
    body = {"category_id": groceries["id"], "amount_minor": 40_000, "currency": "GBP"}
    authed_client.put("/api/budgets", json=body).raise_for_status()
    authed_client.put("/api/budgets", json={**body, "amount_minor": 50_000}).raise_for_status()

    budgets = authed_client.get("/api/budgets").json()
    assert len(budgets) == 1
    assert budgets[0]["amount_minor"] == 50_000


# --- connections ----------------------------------------------------------
def test_provider_list_reports_configuration(authed_client):
    providers = {p["key"]: p for p in authed_client.get("/api/connections/providers").json()}
    assert providers["csv"]["configured"] is True
    assert providers["gocardless"]["configured"] is True  # test env sets the keys
    assert providers["revolut_business"]["configured"] is False  # no private key path
    assert "REVOLUT_PRIVATE_KEY_PATH" in providers["revolut_business"]["missing"]


def test_revolut_business_start_fails_without_a_key(authed_client):
    response = authed_client.post("/api/connections/revolut-business/start", json={})
    assert response.status_code == 400
    assert "PRIVATE_KEY" in response.json()["detail"]


def test_oauth_callback_rejects_an_unknown_state(client):
    response = client.get("/api/connections/gocardless/callback?ref=made-up-state")
    assert response.status_code == 400
    assert "Could not verify" in response.text


def test_dashboard_renders_with_no_data(authed_client):
    data = authed_client.get("/api/analytics/dashboard").json()
    assert data["summary"]["income_minor"] == 0
    assert data["net_worth"]["account_count"] == 0
    assert data["insights"] == []


# --- public deployment hardening ------------------------------------------
def test_database_url_is_normalised_for_sqlalchemy():
    """Hosts hand out postgres:// URLs, a scheme SQLAlchemy dropped."""
    from backend.app.config import normalise_database_url

    assert normalise_database_url("postgres://u:p@host/db") == "postgresql+psycopg://u:p@host/db"
    assert normalise_database_url("postgresql://u:p@host/db") == "postgresql+psycopg://u:p@host/db"
    # Already-explicit and non-Postgres URLs are left alone.
    assert normalise_database_url("postgresql+psycopg://u@h/d") == "postgresql+psycopg://u@h/d"
    assert normalise_database_url("sqlite:///./data/finance.db") == "sqlite:///./data/finance.db"


def test_signup_token_is_not_required_by_default(client):
    assert client.get("/api/auth/status").json()["signup_token_required"] is False


def test_signup_token_gates_registration(client, monkeypatch):
    """On a public URL, the first stranger to find it must not claim the app."""
    from backend.app import routers

    settings = get_settings()
    monkeypatch.setattr(settings, "signup_token", "let-me-in", raising=False)

    assert client.get("/api/auth/status").json()["signup_token_required"] is True

    body = {"email": "me@example.com", "password": "a-long-enough-password"}
    assert client.post("/api/auth/register", json=body).status_code == 403
    assert client.post(
        "/api/auth/register", json={**body, "signup_token": "wrong"}
    ).status_code == 403

    ok = client.post("/api/auth/register", json={**body, "signup_token": "let-me-in"})
    assert ok.status_code == 201


def test_repeated_failed_logins_are_throttled(authed_client, monkeypatch):
    from backend.app.routers import auth as auth_router

    auth_router._failed_logins.clear()
    settings = get_settings()
    monkeypatch.setattr(settings, "login_max_attempts", 3, raising=False)

    wrong = {"email": "me@example.com", "password": "definitely-not-it"}
    for _ in range(3):
        assert authed_client.post("/api/auth/login", json=wrong).status_code == 401

    blocked = authed_client.post("/api/auth/login", json=wrong)
    assert blocked.status_code == 429
    assert "Try again in" in blocked.json()["detail"]

    # The correct password is refused too while locked out, so the lockout
    # can't be sidestepped by guessing right on the next attempt.
    correct = {"email": "me@example.com", "password": "a-long-enough-password"}
    assert authed_client.post("/api/auth/login", json=correct).status_code == 429
    auth_router._failed_logins.clear()


def test_successful_login_clears_the_failure_count(authed_client):
    from backend.app.routers import auth as auth_router

    auth_router._failed_logins.clear()
    authed_client.post("/api/auth/login", json={"email": "me@example.com", "password": "nope"})
    assert auth_router._failed_logins.get("me@example.com")

    authed_client.post(
        "/api/auth/login",
        json={"email": "me@example.com", "password": "a-long-enough-password"},
    ).raise_for_status()
    assert not auth_router._failed_logins.get("me@example.com")
