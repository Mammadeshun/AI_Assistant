"""Categorisation: rules beat provider hints, which beat keyword guesses."""
from __future__ import annotations

from backend.app.models import Rule
from backend.app.services.categorise import Categoriser, ensure_default_categories


def _categoriser(db, user_id):
    ensure_default_categories(db, user_id)
    db.commit()
    return Categoriser(db, user_id)


def test_keyword_matching(db, user_id):
    categoriser = _categoriser(db, user_id)

    groceries = categoriser.categorise("TESCO STORES 3241", None, None, None, None, -4250)
    assert categoriser.categories["Groceries"].id == groceries.category_id
    assert groceries.source == "keyword"

    subscription = categoriser.categorise("NETFLIX.COM", None, None, None, None, -1099)
    assert categoriser.categories["Subscriptions"].id == subscription.category_id


def test_provider_category_wins_over_keywords(db, user_id):
    categoriser = _categoriser(db, user_id)
    # Description says "transfer" but Revolut told us it was an ATM withdrawal.
    result = categoriser.categorise("transfer at machine", None, None, None, "atm", -10000)
    assert result.category_id == categoriser.categories["Cash & ATM"].id
    assert result.source == "provider"


def test_keywords_beat_a_vague_provider_type(db, user_id):
    categoriser = _categoriser(db, user_id)
    # Revolut books an incoming salary as TOPUP; "money moved" must not outrank
    # the description telling us what the money was.
    result = categoriser.categorise("Salary from Employer", None, None, None, "topup", 240_000)
    assert result.category_id == categoriser.categories["Salary"].id

    # With nothing else to go on, the provider type still wins over the fallback.
    plain = categoriser.categorise("FT 8812", None, None, None, "topup", 5_000)
    assert plain.category_id == categoriser.categories["Transfers"].id
    assert plain.source == "provider"


def test_short_keywords_match_whole_words_only(db, user_id):
    categoriser = _categoriser(db, user_id)
    # "tfl" (Transport) is a substring of "neTFLix"; "ee" (utilities) of "greggs".
    assert categoriser.categorise("NETFLIX.COM", None, None, None, None, -1099).category_id == \
        categoriser.categories["Subscriptions"].id
    assert categoriser.categorise("GREGGS 421", None, None, None, None, -350).category_id == \
        categoriser.categories["Restaurants & Cafés"].id
    assert categoriser.categorise("TFL TRAVEL CH", None, None, None, None, -3210).category_id == \
        categoriser.categories["Transport"].id


def test_user_rule_beats_everything(db, user_id):
    categoriser = _categoriser(db, user_id)
    travel = categoriser.categories["Travel"]

    db.add(
        Rule(
            user_id=user_id,
            name="Tesco is actually travel",
            field="description",
            match_type="contains",
            pattern="tesco",
            category_id=travel.id,
            priority=10,
        )
    )
    db.commit()

    result = Categoriser(db, user_id).categorise("TESCO STORES", None, None, None, None, -4250)
    assert result.category_id == travel.id
    assert result.source == "rule"


def test_rule_priority_order(db, user_id):
    categoriser = _categoriser(db, user_id)
    first = categoriser.categories["Travel"]
    second = categoriser.categories["Shopping"]

    db.add(Rule(user_id=user_id, name="low priority", field="description",
                match_type="contains", pattern="acme", category_id=second.id, priority=200))
    db.add(Rule(user_id=user_id, name="high priority", field="description",
                match_type="contains", pattern="acme", category_id=first.id, priority=1))
    db.commit()

    assert Categoriser(db, user_id).categorise("ACME LTD", None, None, None, None, -100).category_id == first.id


def test_regex_rule_and_transfer_flag(db, user_id):
    categoriser = _categoriser(db, user_id)
    db.add(
        Rule(
            user_id=user_id,
            name="Savings sweeps",
            field="description",
            match_type="regex",
            pattern=r"^sweep \d+$",
            category_id=categoriser.categories["Savings & Investments"].id,
            mark_transfer=True,
            priority=5,
        )
    )
    db.commit()

    result = Categoriser(db, user_id).categorise("sweep 42", None, None, None, None, -50000)
    assert result.is_transfer is True
    assert result.source == "rule"


def test_invalid_regex_rule_is_ignored_not_fatal(db, user_id):
    categoriser = _categoriser(db, user_id)
    db.add(Rule(user_id=user_id, name="broken", field="description", match_type="regex",
                pattern="([unclosed", category_id=categoriser.categories["Travel"].id, priority=1))
    db.commit()

    result = Categoriser(db, user_id).categorise("TESCO STORES", None, None, None, None, -100)
    assert result.category_id == categoriser.categories["Groceries"].id


def test_income_fallback(db, user_id):
    categoriser = _categoriser(db, user_id)
    result = categoriser.categorise("Mystery credit", None, None, None, None, 5000)
    assert result.category_id == categoriser.categories["Other Income"].id


def test_unknown_spend_falls_back_to_uncategorised(db, user_id):
    categoriser = _categoriser(db, user_id)
    result = categoriser.categorise("ZZQ payment", None, None, None, None, -1234)
    assert result.category_id == categoriser.categories["Uncategorised"].id


def test_defaults_are_idempotent(db, user_id):
    first = ensure_default_categories(db, user_id)
    db.commit()
    second = ensure_default_categories(db, user_id)
    db.commit()
    assert {c.id for c in first.values()} == {c.id for c in second.values()}


def test_italian_merchants_are_recognised(db, user_id):
    # A statement from an Italian account is mostly names the UK keyword list
    # has never heard of.
    categoriser = _categoriser(db, user_id)
    expected = {
        "Eurospin": "Groceries",
        "Esselunga": "Groceries",
        "Trenitalia": "Transport",
        "ATM - Azienda Trasporti Milanesi": "Transport",
        "Bar Sottovento": "Restaurants & Cafés",
        "Il Caffè all'Università": "Restaurants & Cafés",
        "Farmacia Formaggia": "Health & Fitness",
        "iliad": "Bills & Utilities",
        "Tigotà": "Shopping",
    }
    for merchant, category in expected.items():
        result = categoriser.categorise(merchant, merchant, None, None, None, -500)
        assert result.category_id == categoriser.categories[category].id, merchant


def test_the_milan_transport_operator_is_not_a_cash_machine(db, user_id):
    # "ATM" is Milan's bus company as well as a hole in the wall.
    categoriser = _categoriser(db, user_id)
    result = categoriser.categorise(
        "ATM - Azienda Trasporti Milanesi", "ATM - Azienda Trasporti Milanesi",
        None, None, None, -200,
    )
    assert result.category_id == categoriser.categories["Transport"].id

    withdrawal = categoriser.categorise("Cash withdrawal", None, None, None, "atm", -5000)
    assert withdrawal.category_id == categoriser.categories["Cash & ATM"].id


def test_payments_to_people_are_transfers_not_shopping(db, user_id):
    # The counterparty is a person; merchant keywords must not be matched
    # against their name.
    categoriser = _categoriser(db, user_id)
    result = categoriser.categorise("To Md Shamsuddin", "Md Shamsuddin", None, None, None, -2000)

    assert result.category_id == categoriser.categories["Transfers"].id
    assert result.is_transfer is True


def test_short_chain_names_only_match_the_whole_merchant(db, user_id):
    categoriser = _categoriser(db, user_id)

    chain = categoriser.categorise("MD", "MD", None, None, None, -1500)
    assert chain.category_id == categoriser.categories["Groceries"].id

    person = categoriser.categorise("Md Shamsuddin", "Md Shamsuddin", None, None, None, -1500)
    assert person.category_id != categoriser.categories["Groceries"].id
