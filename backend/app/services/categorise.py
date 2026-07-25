"""Transaction categorisation.

Three layers, applied in order:
  1. User rules (highest priority wins, lower `priority` number = earlier).
  2. Revolut's own category / merchant category code, mapped to our categories.
  3. Keyword heuristics over the description and merchant.

A transaction with `category_locked = True` was categorised by hand and is
never overwritten.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Category, CategoryKind, Rule, Transaction

# name -> (kind, colour, icon)
DEFAULT_CATEGORIES: dict[str, tuple[CategoryKind, str, str]] = {
    "Groceries": (CategoryKind.expense, "#4caf50", "🛒"),
    "Restaurants & Cafés": (CategoryKind.expense, "#ff7043", "🍽️"),
    "Transport": (CategoryKind.expense, "#42a5f5", "🚆"),
    "Fuel & Parking": (CategoryKind.expense, "#5c6bc0", "⛽"),
    "Shopping": (CategoryKind.expense, "#ec407a", "🛍️"),
    "Bills & Utilities": (CategoryKind.expense, "#26a69a", "💡"),
    "Rent & Mortgage": (CategoryKind.expense, "#8d6e63", "🏠"),
    "Subscriptions": (CategoryKind.expense, "#ab47bc", "🔁"),
    "Health & Fitness": (CategoryKind.expense, "#66bb6a", "💊"),
    "Entertainment": (CategoryKind.expense, "#ffa726", "🎬"),
    "Travel": (CategoryKind.expense, "#29b6f6", "✈️"),
    "Cash & ATM": (CategoryKind.expense, "#78909c", "💵"),
    "Fees & Charges": (CategoryKind.expense, "#ef5350", "🏷️"),
    "Education": (CategoryKind.expense, "#7e57c2", "📚"),
    "Gifts & Donations": (CategoryKind.expense, "#f06292", "🎁"),
    "Insurance": (CategoryKind.expense, "#546e7a", "🛡️"),
    "Savings & Investments": (CategoryKind.transfer, "#00897b", "📈"),
    "Transfers": (CategoryKind.transfer, "#90a4ae", "↔️"),
    "Salary": (CategoryKind.income, "#2e7d32", "💼"),
    "Other Income": (CategoryKind.income, "#43a047", "➕"),
    "Uncategorised": (CategoryKind.expense, "#9e9e9e", "❓"),
}

# Keyword heuristics, matched case-insensitively against merchant + description.
# Matching is whole-word (see _compile_keyword): a plain substring test would put
# "NETFLIX" in Transport, because "tfl" hides inside "neTFLix".
KEYWORD_MAP: list[tuple[str, tuple[str, ...]]] = [
    ("Groceries", ("tesco", "sainsbury", "asda", "aldi", "lidl", "morrisons", "waitrose",
                   "co-op", "coop food", "coop", "iceland", "whole foods", "spar",
                   "carrefour", "mercadona", "albert heijn", "rewe", "edeka", "grocer",
                   # Italian chains
                   "eurospin", "esselunga", "conad", "pam", "in's mercato", "penny market",
                   "bennet", "famila", "todis", "crai", "iper", "prix", "despar",
                   "il gigante", "alimentari", "supermercato", "panificio")),
    ("Restaurants & Cafés", ("starbucks", "costa", "pret", "cafe", "café", "coffee",
                             "mcdonald", "kfc", "burger", "pizza", "nando", "wagamama",
                             "deliveroo", "uber eats", "ubereats", "just eat", "justeat",
                             "restaurant", "greggs", "subway", "dominos", "bakery", "pub",
                             # Italian: the bar is where the coffee happens
                             "bar", "caffe", "caffè", "cafè", "trattoria", "osteria",
                             "pizzeria", "ristorante", "gelateria", "pasticceria",
                             "rosticceria", "paninoteca", "birreria", "enoteca", "forno",
                             "glovo", "selecta")),
    ("Transport", ("tfl", "transport for london", "trainline", "national rail", "uber",
                   "bolt", "lyft", "bus", "metro", "railway", "citymapper", "lime",
                   "sncf", "db bahn", "taxi",
                   # Italian rail, city transport and ticketing
                   "trenitalia", "trenord", "italo treno", "my cicero", "mycicero",
                   "azienda trasporti", "atac", "flixbus", "blablacar", "freenow",
                   "autolinee", "autoservizi", "biglietto")),
    ("Fuel & Parking", ("shell", "bp", "esso", "texaco", "petrol", "fuel", "parking",
                        "ringgo", "parkopedia", "euro garages", "gulf",
                        "eni station", "q8", "tamoil", "autostrade", "telepass",
                        "benzina", "carburante", "parcheggio")),
    ("Shopping", ("amazon", "ebay", "argos", "asos", "zara", "h&m", "primark", "ikea",
                  "next retail", "john lewis", "aliexpress", "etsy", "shein", "decathlon",
                  "uniqlo", "sports direct",
                  # Italian and pan-European high street
                  "tigotà", "tigota", "action", "kik", "temu", "bershka", "media world",
                  "mediaworld", "unieuro", "zalando", "acqua e sapone", "pull&bear",
                  "stradivarius", "oysho", "flying tiger", "tedi", "leroy merlin",
                  "tabaccheria", "cartoleria", "libreria", "profumeria")),
    ("Bills & Utilities", ("british gas", "octopus energy", "edf", "e.on", "eon",
                           "ovo energy", "thames water", "water plc", "council tax",
                           "vodafone", "o2", "ee", "three", "giffgaff", "virgin media",
                           "sky uk", "bt group", "broadband", "electric", "utility",
                           # Italian telecoms, utilities and government payments
                           "iliad", "windtre", "wind tre", "fastweb", "ho mobile",
                           "very mobile", "enel", "a2a", "acea", "sorgenia", "pagopa",
                           "canone rai", "bolletta")),
    ("Rent & Mortgage", ("rent", "landlord", "mortgage", "letting", "property manage",
                         "affitto", "locazione")),
    ("Subscriptions", ("netflix", "spotify", "disney", "apple.com/bill", "apple music",
                       "icloud", "google storage", "youtube premium", "amazon prime",
                       "audible", "patreon", "adobe", "microsoft 365", "openai", "notion",
                       "dropbox", "github", "subscription")),
    ("Health & Fitness", ("pharmacy", "boots", "superdrug", "nhs", "dentist", "optician",
                          "gym", "puregym", "fitness", "peloton", "clinic", "hospital",
                          "doctor",
                          "farmacia", "parafarmacia", "dentistico", "dentista",
                          "ospedale", "poliambulatorio", "centro medico", "ottica")),
    ("Entertainment", ("cinema", "odeon", "vue", "cineworld", "theatre", "ticketmaster",
                       "steam games", "playstation", "xbox", "nintendo", "eventbrite",
                       "concert")),
    ("Travel", ("airbnb", "booking.com", "hotel", "hostel", "ryanair", "easyjet",
                "british airways", "wizz air", "lufthansa", "expedia", "skyscanner",
                "eurostar", "airlines", "airways", "flight")),
    ("Cash & ATM", ("atm", "cash withdrawal", "cash at", "withdrawal")),
    ("Fees & Charges", ("fee", "charge", "interest charged", "overdraft", "commission")),
    ("Education", ("university", "college", "tuition", "udemy", "coursera", "school",
                   "student loan", "università", "universita", "politecnico",
                   "telematica", "tassa universitaria")),
    ("Gifts & Donations", ("charity", "donation", "justgiving", "gofundme", "oxfam",
                           "unicef", "red cross")),
    ("Insurance", ("insurance", "aviva", "axa", "admiral", "direct line", "churchill",
                   "hastings")),
    ("Savings & Investments", ("vanguard", "trading 212", "trading212", "freetrade",
                               "coinbase", "kraken", "binance", "etoro", "hargreaves",
                               "nutmeg", "isa", "pension", "vault", "savings",
                               "trade republic", "revpoints", "spare change",
                               "round-up", "roundup")),
    ("Salary", ("salary", "payroll", "wages", "hmrc paye")),
    ("Transfers", ("transfer", "to revolut", "from revolut", "top-up", "topup",
                   "exchange", "faster payment")),
]


def _compile_keyword(keyword: str) -> re.Pattern[str]:
    """Whole-word matcher.

    Lookarounds rather than \\b so keywords containing punctuation
    ("apple.com/bill", "co-op", "h&m") still anchor correctly.
    """
    return re.compile(rf"(?<![a-z0-9]){re.escape(keyword)}(?![a-z0-9])", re.IGNORECASE)


COMPILED_KEYWORDS: list[tuple[str, tuple[re.Pattern[str], ...]]] = [
    (name, tuple(_compile_keyword(keyword) for keyword in keywords))
    for name, keywords in KEYWORD_MAP
]


# Revolut transaction types / MCC codes -> category.
PROVIDER_CATEGORY_MAP: dict[str, str] = {
    "atm": "Cash & ATM",
    "fee": "Fees & Charges",
    "card_credit": "Other Income",
    "card_refund": "Shopping",
    "exchange": "Transfers",
    "transfer": "Transfers",
    "topup": "Transfers",
    "loan": "Fees & Charges",
    "tax": "Bills & Utilities",
    "5411": "Groceries",
    "5812": "Restaurants & Cafés",
    "5814": "Restaurants & Cafés",
    "5541": "Fuel & Parking",
    "4111": "Transport",
    "4121": "Transport",
    "5912": "Health & Fitness",
    "7997": "Health & Fitness",
    "5732": "Shopping",
    "7011": "Travel",
    "4511": "Travel",
}

# These provider types only say "money moved", not what for — a salary paid in
# arrives as a TOPUP. Keywords get first refusal on them; the rest of the map
# (ATM, fees, MCC codes) is specific enough to trust outright.
WEAK_PROVIDER_CATEGORIES = {"topup", "transfer", "exchange"}

TRANSFER_HINTS = ("transfer to", "transfer from", "to savings", "own account", "internal")

# Revolut labels a payment between people "To Anna Rossi" / "From Marco Bianchi".
# The name is a person, not a shop, so merchant keywords must not be matched
# against it.
PERSON_TRANSFER = re.compile(r"^(to|from)\s+\S", re.IGNORECASE)

# Chains whose name is too short or too common to search for inside a longer
# string: "MD" is an Italian supermarket, but "Md Shamsuddin" is a person. These
# are matched against the whole merchant name and nothing less.
EXACT_MERCHANTS: dict[str, str] = {
    "md": "Groceries",
}


@dataclass(slots=True)
class Categorisation:
    category_id: int | None
    merchant: str | None
    is_transfer: bool
    source: str  # "rule" | "provider" | "keyword" | "fallback"


def ensure_default_categories(db: Session, user_id: int) -> dict[str, Category]:
    """Create any missing system categories and return them keyed by name."""
    existing = {
        category.name: category
        for category in db.scalars(select(Category).where(Category.user_id == user_id)).all()
    }
    created = False
    for name, (kind, colour, icon) in DEFAULT_CATEGORIES.items():
        if name not in existing:
            category = Category(
                user_id=user_id, name=name, kind=kind, colour=colour, icon=icon, is_system=True
            )
            db.add(category)
            existing[name] = category
            created = True
    if created:
        db.flush()
    return existing


class Categoriser:
    """Built once per sync/recategorise pass, then reused for every transaction."""

    def __init__(self, db: Session, user_id: int) -> None:
        self.categories = ensure_default_categories(db, user_id)
        self.by_name = {name.lower(): category for name, category in self.categories.items()}
        self.rules = db.scalars(
            select(Rule)
            .where(Rule.user_id == user_id, Rule.is_active.is_(True))
            .order_by(Rule.priority.asc(), Rule.id.asc())
        ).all()
        self._compiled: dict[int, re.Pattern[str]] = {}
        for rule in self.rules:
            if rule.match_type == "regex":
                try:
                    self._compiled[rule.id] = re.compile(rule.pattern, re.IGNORECASE)
                except re.error:
                    continue

    # -- public ----------------------------------------------------------
    def categorise(
        self,
        description: str,
        merchant: str | None,
        counterparty: str | None,
        reference: str | None,
        provider_category: str | None,
        amount_minor: int,
    ) -> Categorisation:
        fields = {
            "description": description or "",
            "merchant": merchant or "",
            "counterparty": counterparty or "",
            "reference": reference or "",
        }

        match = self._match_rule(fields)
        if match:
            rule = match
            return Categorisation(
                category_id=rule.category_id,
                merchant=rule.set_merchant or merchant,
                is_transfer=rule.mark_transfer,
                source="rule",
            )

        haystack = " ".join(fields.values()).lower()
        is_transfer = any(hint in haystack for hint in TRANSFER_HINTS)

        if PERSON_TRANSFER.match((description or "").strip()):
            return Categorisation(self._id("Transfers"), merchant, True, "keyword")

        exact = EXACT_MERCHANTS.get((merchant or description or "").strip().lower())
        if exact:
            return Categorisation(self._id(exact), merchant, is_transfer, "keyword")

        provider_name = self._from_provider_category(provider_category)
        weak = (provider_category or "").strip().lower() in WEAK_PROVIDER_CATEGORIES

        if provider_name and not weak:
            return Categorisation(self._id(provider_name), merchant, is_transfer, "provider")

        name = self._from_keywords(haystack)
        if name:
            return Categorisation(self._id(name), merchant, is_transfer, "keyword")

        if provider_name:
            return Categorisation(self._id(provider_name), merchant, is_transfer, "provider")

        if is_transfer:
            return Categorisation(self._id("Transfers"), merchant, True, "keyword")
        if amount_minor > 0:
            return Categorisation(self._id("Other Income"), merchant, False, "fallback")
        return Categorisation(self._id("Uncategorised"), merchant, False, "fallback")

    # -- internals -------------------------------------------------------
    def _match_rule(self, fields: dict[str, str]) -> Rule | None:
        for rule in self.rules:
            value = fields.get(rule.field, "")
            if not value:
                continue
            if self._rule_matches(rule, value):
                return rule
        return None

    def _rule_matches(self, rule: Rule, value: str) -> bool:
        haystack = value.lower()
        needle = rule.pattern.lower()
        if rule.match_type == "equals":
            return haystack.strip() == needle.strip()
        if rule.match_type == "starts_with":
            return haystack.startswith(needle)
        if rule.match_type == "regex":
            compiled = self._compiled.get(rule.id)
            return bool(compiled and compiled.search(value))
        return needle in haystack

    def _from_provider_category(self, provider_category: str | None) -> str | None:
        if not provider_category:
            return None
        key = provider_category.strip().lower()
        if key in PROVIDER_CATEGORY_MAP:
            return PROVIDER_CATEGORY_MAP[key]
        # Revolut's retail categories often already match one of ours.
        pretty = key.replace("_", " ").strip()
        for name in self.categories:
            if name.lower() == pretty:
                return name
        return None

    def _from_keywords(self, haystack: str) -> str | None:
        for name, patterns in COMPILED_KEYWORDS:
            for pattern in patterns:
                if pattern.search(haystack):
                    return name
        return None

    def _id(self, name: str) -> int | None:
        category = self.categories.get(name)
        return category.id if category else None


def recategorise_all(db: Session, user_id: int, include_locked: bool = False) -> int:
    """Re-run categorisation over stored transactions. Returns rows changed."""
    categoriser = Categoriser(db, user_id)
    query = select(Transaction).where(Transaction.user_id == user_id)
    if not include_locked:
        query = query.where(Transaction.category_locked.is_(False))

    changed = 0
    for txn in db.scalars(query).all():
        result = categoriser.categorise(
            txn.description,
            txn.merchant,
            txn.counterparty,
            txn.reference,
            txn.provider_category,
            txn.amount_minor,
        )
        if txn.category_id != result.category_id or txn.is_transfer != result.is_transfer:
            txn.category_id = result.category_id
            txn.is_transfer = result.is_transfer
            if result.merchant:
                txn.merchant = result.merchant
            changed += 1
    db.commit()
    return changed
