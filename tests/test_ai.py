"""AI features. No test touches the network — the Anthropic client is faked."""
from __future__ import annotations

import itertools
from datetime import date
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from backend.app.config import get_settings
from backend.app.models import Account, Category, Rule, Transaction
from backend.app.services import ai as ai_service
from backend.app.services import ai_agent
from backend.app.services.ai import AIError, AIUnavailable, MerchantVerdict, MerchantVerdicts
from backend.app.services.categorise import ensure_default_categories


# --------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------
@pytest.fixture
def account(db, user_id):
    record = Account(
        user_id=user_id, external_id="a1", name="Revolut GBP", currency="GBP", balance_minor=100_000
    )
    db.add(record)
    db.commit()
    db.refresh(record)
    return record


@pytest.fixture
def categories(db, user_id):
    result = ensure_default_categories(db, user_id)
    db.commit()
    return result


_counter = itertools.count()


def add_txn(db, account, description, amount_minor=-1000, day=None, category_id=None):
    txn = Transaction(
        user_id=account.user_id,
        account_id=account.id,
        dedupe_key=f"{description}-{amount_minor}-{next(_counter)}",
        booked_at=day or date.today(),
        amount_minor=amount_minor,
        currency=account.currency,
        description=description,
        merchant=description,
        category_id=category_id,
    )
    db.add(txn)
    db.commit()
    return txn


def fake_response(*, stop_reason="end_turn", text="", parsed=None, stop_details=None):
    blocks = [SimpleNamespace(type="text", text=text)] if text else []
    return SimpleNamespace(
        stop_reason=stop_reason,
        stop_details=stop_details,
        content=blocks,
        parsed_output=parsed,
        usage=SimpleNamespace(input_tokens=100, output_tokens=50),
    )


# --------------------------------------------------------------------------
# Availability and safety rails
# --------------------------------------------------------------------------
def test_disabled_without_api_key():
    settings = get_settings()
    assert settings.anthropic_api_key == ""
    status = ai_service.ai_status(settings)
    assert status["enabled"] is False
    assert "ANTHROPIC_API_KEY" in status["reason"]

    with pytest.raises(AIUnavailable, match="ANTHROPIC_API_KEY"):
        ai_service.get_client(settings)


def test_ai_endpoints_report_unavailable(authed_client):
    assert authed_client.get("/api/ai/status").json()["enabled"] is False
    for path in ["/api/ai/chat", "/api/ai/categorise", "/api/ai/briefing"]:
        body = {"message": "hi"} if path.endswith("chat") else None
        response = authed_client.post(path, json=body)
        assert response.status_code == 503, path
        assert "ANTHROPIC_API_KEY" in response.json()["detail"]


def test_ai_endpoints_require_a_session(client):
    assert client.get("/api/ai/status").status_code == 401
    assert client.post("/api/ai/chat", json={"message": "hi"}).status_code == 401


def test_refusal_is_surfaced_not_read_as_content():
    """A refusal must never be treated as an answer."""
    response = fake_response(
        stop_reason="refusal", stop_details=SimpleNamespace(category="cyber", explanation="no")
    )
    with pytest.raises(AIError, match="declined"):
        ai_service.text_of(response)


def test_text_of_reads_normal_responses():
    assert ai_service.text_of(fake_response(text="You spent £42.50.")) == "You spent £42.50."


def test_effort_is_omitted_on_models_that_reject_it():
    """Haiku 4.5 has no effort parameter — sending one is a 400."""
    assert ai_service.supports_effort("claude-opus-5") is True
    assert ai_service.supports_effort("claude-sonnet-5") is True
    assert ai_service.supports_effort("claude-haiku-4-5") is False
    assert ai_service.supports_effort("claude-sonnet-4-5") is False
    # An unrecognised (newer) model is assumed to support it.
    assert ai_service.supports_effort("claude-opus-9") is True


def test_default_model_request_carries_no_effort(monkeypatch):
    settings = get_settings()
    assert settings.ai_model == "claude-haiku-4-5"  # the shipped default
    assert ai_service.request_kwargs(settings) == {}


def test_opus_request_carries_effort_and_fallback(monkeypatch):
    settings = get_settings().model_copy(update={"ai_model": "claude-opus-5"})
    kwargs = ai_service.request_kwargs(settings)
    assert kwargs["output_config"] == {"effort": settings.ai_effort}
    assert kwargs["fallbacks"] == "default"
    assert ai_service.FALLBACK_BETA in kwargs["betas"]


def test_fallback_is_only_sent_to_models_that_accept_it():
    assert ai_service.supports_server_side_fallback("claude-opus-5") is True
    assert ai_service.supports_server_side_fallback("claude-fable-5") is True
    assert ai_service.supports_server_side_fallback("claude-haiku-4-5") is False
    assert ai_service.supports_server_side_fallback("claude-sonnet-5") is False


def test_rejected_optional_parameter_retries_without_it():
    """A model whose support differs from ours must not cost the user a question."""
    import anthropic

    settings = get_settings().model_copy(update={"ai_model": "claude-opus-5"})
    attempts = []

    def make_request(extra):
        attempts.append(extra)
        if extra:
            raise anthropic.BadRequestError(
                message="unexpected parameter: fallbacks",
                response=MagicMock(status_code=400),
                body=None,
            )
        return "ok"

    assert ai_service.call_with_fallback_retry(make_request, settings) == "ok"
    assert len(attempts) > 1
    assert attempts[0] != {} and attempts[-1] == {}


def test_unrelated_bad_request_is_not_retried():
    import anthropic

    settings = get_settings()

    def make_request(extra):
        raise anthropic.BadRequestError(
            message="max_tokens is too large", response=MagicMock(status_code=400), body=None
        )

    with pytest.raises(anthropic.BadRequestError):
        ai_service.call_with_fallback_retry(make_request, settings)


# --------------------------------------------------------------------------
# Categorisation
# --------------------------------------------------------------------------
def test_uncategorised_merchants_are_collected_by_frequency(db, user_id, account, categories):
    uncategorised = categories["Uncategorised"].id
    for _ in range(3):
        add_txn(db, account, "ZZQ TRADING LTD", category_id=uncategorised)
    add_txn(db, account, "ODD PAYMENT CO", category_id=uncategorised)
    add_txn(db, account, "TESCO STORES", category_id=categories["Groceries"].id)

    found = ai_service._uncategorised_merchants(db, user_id, limit=10)
    assert found[0] == ("ZZQ TRADING LTD", 3)
    assert ("ODD PAYMENT CO", 1) in found
    assert all(name != "TESCO STORES" for name, _ in found)


def _patch_client(monkeypatch, response):
    client = MagicMock()
    client.beta.messages.parse.return_value = response
    client.beta.messages.create.return_value = response
    monkeypatch.setattr(ai_service, "get_client", lambda settings=None: client)
    return client


def test_categorisation_writes_rules_and_recategorises(db, user_id, account, categories, monkeypatch):
    uncategorised = categories["Uncategorised"].id
    add_txn(db, account, "ZZQ TRADING LTD", category_id=uncategorised)

    parsed = MerchantVerdicts(
        verdicts=[
            MerchantVerdict(
                merchant="ZZQ TRADING LTD", category="Shopping", confidence=0.9, clean_name="ZZQ"
            )
        ]
    )
    _patch_client(monkeypatch, fake_response(parsed=parsed))

    result = ai_service.categorise_uncategorised(db, user_id)

    assert result.merchants_reviewed == 1
    assert result.rules_created == 1
    assert result.transactions_recategorised >= 1

    rule = db.query(Rule).filter(Rule.user_id == user_id).one()
    assert rule.pattern == "ZZQ TRADING LTD"
    assert rule.category_id == categories["Shopping"].id
    assert rule.name.startswith("AI:")

    txn = db.query(Transaction).filter(Transaction.user_id == user_id).one()
    assert txn.category_id == categories["Shopping"].id


def test_low_confidence_verdicts_are_not_applied(db, user_id, account, categories, monkeypatch):
    add_txn(db, account, "MYSTERY CO", category_id=categories["Uncategorised"].id)
    parsed = MerchantVerdicts(
        verdicts=[
            MerchantVerdict(
                merchant="MYSTERY CO", category="Travel", confidence=0.2, clean_name="Mystery"
            )
        ]
    )
    _patch_client(monkeypatch, fake_response(parsed=parsed))

    result = ai_service.categorise_uncategorised(db, user_id, min_confidence=0.6)

    assert result.rules_created == 0
    assert result.skipped_low_confidence == 1
    assert db.query(Rule).count() == 0


def test_hallucinated_merchant_is_ignored(db, user_id, account, categories, monkeypatch):
    """The model must not be able to write a rule for a merchant we never sent."""
    add_txn(db, account, "REAL MERCHANT", category_id=categories["Uncategorised"].id)
    parsed = MerchantVerdicts(
        verdicts=[
            MerchantVerdict(
                merchant="NEVER SENT LTD", category="Travel", confidence=0.99, clean_name="Ghost"
            )
        ]
    )
    _patch_client(monkeypatch, fake_response(parsed=parsed))

    result = ai_service.categorise_uncategorised(db, user_id)
    assert result.rules_created == 0
    assert db.query(Rule).count() == 0


def test_unknown_category_is_ignored(db, user_id, account, categories, monkeypatch):
    add_txn(db, account, "SOME SHOP", category_id=categories["Uncategorised"].id)
    parsed = MerchantVerdicts(
        verdicts=[
            MerchantVerdict(
                merchant="SOME SHOP", category="Yacht Maintenance", confidence=0.99, clean_name="Shop"
            )
        ]
    )
    _patch_client(monkeypatch, fake_response(parsed=parsed))

    assert ai_service.categorise_uncategorised(db, user_id).rules_created == 0


def test_categorisation_no_work_makes_no_api_call(db, user_id, account, categories, monkeypatch):
    add_txn(db, account, "TESCO", category_id=categories["Groceries"].id)
    client = _patch_client(monkeypatch, fake_response(parsed=MerchantVerdicts(verdicts=[])))

    result = ai_service.categorise_uncategorised(db, user_id)
    assert result.merchants_reviewed == 0
    client.beta.messages.parse.assert_not_called()


def test_categorisation_refusal_becomes_an_error(db, user_id, account, categories, monkeypatch):
    add_txn(db, account, "SOMETHING ODD", category_id=categories["Uncategorised"].id)
    _patch_client(monkeypatch, fake_response(stop_reason="refusal"))

    with pytest.raises(AIError, match="declined"):
        ai_service.categorise_uncategorised(db, user_id)


# --------------------------------------------------------------------------
# Briefing
# --------------------------------------------------------------------------
def test_briefing_skips_the_api_when_data_is_thin(db, user_id, account, monkeypatch):
    client = _patch_client(monkeypatch, fake_response(text="unused"))
    add_txn(db, account, "One thing")

    text = ai_service.monthly_briefing(db, user_id, "GBP")
    assert "Not enough activity" in text
    client.beta.messages.create.assert_not_called()


def test_briefing_sends_figures_and_returns_prose(db, user_id, account, categories, monkeypatch):
    for index in range(4):
        add_txn(db, account, f"Shop {index}", amount_minor=-2500 - index)
    client = _patch_client(monkeypatch, fake_response(text="You are spending steadily."))

    text = ai_service.monthly_briefing(db, user_id, "GBP")
    assert text == "You are spending steadily."

    prompt = client.beta.messages.create.call_args.kwargs["messages"][0]["content"]
    assert "money out" in prompt
    assert "GBP" in prompt


# --------------------------------------------------------------------------
# Chat agent tools
# --------------------------------------------------------------------------
def _tools_by_name(db, user):
    return {tool.name: tool for tool in ai_agent.build_tools(db, user)}


@pytest.fixture
def user_obj(db, user_id):
    from backend.app.models import User

    return db.get(User, user_id)


def test_tools_expose_only_read_operations(db, user_obj):
    names = set(_tools_by_name(db, user_obj))
    assert names == {
        "search_transactions",
        "spending_summary",
        "category_breakdown",
        "cash_flow",
        "recurring_payments",
        "budgets_status",
        "accounts_overview",
        "top_merchants",
    }
    # Every tool is a query. Nothing here writes, deletes, or moves money —
    # the verbs a mutating tool would start with are absent by construction.
    mutating_verbs = ("create_", "delete_", "update_", "add_", "send_", "pay_", "transfer_", "set_")
    assert not any(name.startswith(verb) for name in names for verb in mutating_verbs)


def test_search_transactions_tool(db, user_obj, account, categories):
    add_txn(db, account, "TESCO STORES", amount_minor=-4250, category_id=categories["Groceries"].id)
    add_txn(db, account, "NETFLIX", amount_minor=-1099)

    tool = _tools_by_name(db, user_obj)["search_transactions"]
    output = tool.call({"query": "tesco"})

    assert "TESCO STORES" in output
    assert "-42.50 GBP" in output
    assert "Groceries" in output
    assert "NETFLIX" not in output


def test_search_transactions_reports_an_unknown_category(db, user_obj, account, categories):
    tool = _tools_by_name(db, user_obj)["search_transactions"]
    output = tool.call({"category": "Yachts"})
    assert "No category named 'Yachts'" in output
    assert "Groceries" in output  # tells the model what it can use instead


def test_search_transactions_direction_filter(db, user_obj, account):
    add_txn(db, account, "SALARY", amount_minor=200_000)
    add_txn(db, account, "SHOP", amount_minor=-5_000)

    tool = _tools_by_name(db, user_obj)["search_transactions"]
    assert "SALARY" in tool.call({"direction": "in"})
    assert "SHOP" not in tool.call({"direction": "in"})
    assert "SHOP" in tool.call({"direction": "out"})


def test_search_transactions_row_cap(db, user_obj, account):
    for index in range(80):
        add_txn(db, account, f"SHOP {index}", amount_minor=-100 - index)

    output = _tools_by_name(db, user_obj)["search_transactions"].call({"limit": 500})
    assert "80 transaction(s) matched" in output
    assert output.count("GBP |") <= ai_agent.MAX_ROWS


def test_spending_summary_tool(db, user_obj, account):
    add_txn(db, account, "SALARY", amount_minor=200_000)
    add_txn(db, account, "RENT", amount_minor=-90_000)

    output = _tools_by_name(db, user_obj)["spending_summary"].call({})
    assert "money in: 2000.00 GBP" in output
    assert "money out: 900.00 GBP" in output
    assert "net: 1100.00 GBP" in output


def test_summary_keeps_currencies_separate(db, user_obj, account, user_id):
    add_txn(db, account, "GBP SPEND", amount_minor=-10_000)
    euro = Transaction(
        user_id=user_id,
        account_id=account.id,
        dedupe_key="eur-1",
        booked_at=date.today(),
        amount_minor=-5_000,
        currency="EUR",
        description="EUR SPEND",
    )
    db.add(euro)
    db.commit()

    output = _tools_by_name(db, user_obj)["spending_summary"].call({})
    assert "money out: 100.00 GBP" in output
    assert "also held in EUR" in output
    assert "not converted" in output


def test_accounts_overview_tool(db, user_obj, account):
    output = _tools_by_name(db, user_obj)["accounts_overview"].call({})
    assert "Revolut GBP" in output
    assert "1000.00 GBP" in output


def test_empty_tools_answer_honestly(db, user_obj):
    tools = _tools_by_name(db, user_obj)
    assert "No accounts" in tools["accounts_overview"].call({})
    assert "No budgets" in tools["budgets_status"].call({})
    assert "No recurring payments" in tools["recurring_payments"].call({})
    assert "No transactions matched" in tools["search_transactions"].call({"query": "nothing"})


# --------------------------------------------------------------------------
# Chat orchestration
# --------------------------------------------------------------------------
def test_trim_history_keeps_a_valid_opening_turn():
    history = [
        {"role": "user", "content": "a"},
        {"role": "assistant", "content": "b"},
        {"role": "user", "content": "c"},
        {"role": "assistant", "content": "d"},
    ]
    trimmed = ai_agent.trim_history(history, max_turns=3)
    assert trimmed[0]["role"] == "user"  # never starts on an assistant turn


def test_chat_records_tool_calls_and_returns_the_reply(db, user_obj, monkeypatch):
    message = SimpleNamespace(
        content=[
            SimpleNamespace(type="tool_use", name="spending_summary", input={"months_back": 1}),
            SimpleNamespace(type="text", text="You spent £120 last month."),
        ],
        stop_reason="end_turn",
        usage=SimpleNamespace(input_tokens=500, output_tokens=80),
    )
    client = MagicMock()
    client.beta.messages.tool_runner.return_value = iter([message])
    monkeypatch.setattr(ai_agent, "get_client", lambda settings=None: client)

    result = ai_agent.chat(db, user_obj, [{"role": "user", "content": "what did I spend?"}])

    assert result.reply == "You spent £120 last month."
    assert [call.name for call in result.tool_calls] == ["spending_summary"]
    assert result.input_tokens == 500

    sent = client.beta.messages.tool_runner.call_args.kwargs
    assert sent["model"] == get_settings().ai_model
    assert sent["max_iterations"] == ai_agent.MAX_TOOL_ITERATIONS
    # Default model is Haiku, which rejects both optional parameters.
    assert "output_config" not in sent
    assert "fallbacks" not in sent


def test_chat_on_opus_sends_effort_and_fallback(db, user_obj, monkeypatch):
    message = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="ok")],
        stop_reason="end_turn",
        usage=SimpleNamespace(input_tokens=10, output_tokens=5),
    )
    client = MagicMock()
    client.beta.messages.tool_runner.return_value = iter([message])
    monkeypatch.setattr(ai_agent, "get_client", lambda settings=None: client)

    settings = get_settings().model_copy(update={"ai_model": "claude-opus-5"})
    ai_agent.chat(db, user_obj, [{"role": "user", "content": "hi"}], settings)

    sent = client.beta.messages.tool_runner.call_args.kwargs
    assert sent["model"] == "claude-opus-5"
    assert sent["output_config"] == {"effort": settings.ai_effort}
    assert sent["fallbacks"] == "default"


def test_chat_surfaces_a_refusal(db, user_obj, monkeypatch):
    message = SimpleNamespace(
        content=[], stop_reason="refusal", stop_details=SimpleNamespace(category="cyber"),
        usage=SimpleNamespace(input_tokens=10, output_tokens=0),
    )
    client = MagicMock()
    client.beta.messages.tool_runner.return_value = iter([message])
    monkeypatch.setattr(ai_agent, "get_client", lambda settings=None: client)

    with pytest.raises(AIError, match="declined"):
        ai_agent.chat(db, user_obj, [{"role": "user", "content": "hello"}])


def test_chat_endpoint_persists_the_conversation(authed_client, monkeypatch):
    message = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="You spent £42.50 at Tesco.")],
        stop_reason="end_turn",
        usage=SimpleNamespace(input_tokens=200, output_tokens=30),
    )
    client = MagicMock()
    client.beta.messages.tool_runner.return_value = iter([message])
    monkeypatch.setattr(ai_agent, "get_client", lambda settings=None: client)
    monkeypatch.setattr(
        "backend.app.routers.ai._require_ai", lambda settings: None
    )

    first = authed_client.post("/api/ai/chat", json={"message": "how much at tesco?"})
    assert first.status_code == 200
    thread_id = first.json()["thread_id"]
    assert first.json()["reply"] == "You spent £42.50 at Tesco."

    stored = authed_client.get(f"/api/ai/threads/{thread_id}").json()
    assert [row["role"] for row in stored] == ["user", "assistant"]

    threads = authed_client.get("/api/ai/threads").json()
    assert len(threads) == 1
    assert threads[0]["title"].startswith("how much")

    assert authed_client.delete(f"/api/ai/threads/{thread_id}").status_code == 204
    assert authed_client.get("/api/ai/threads").json() == []


def test_chat_thread_belongs_to_its_owner(authed_client):
    assert authed_client.get("/api/ai/threads/999").status_code == 404
