"""Claude-powered features: merchant categorisation and written insights.

Privacy: nothing here runs unless you set ANTHROPIC_API_KEY. When it does run,
only the text that identifies a merchant (and, for insights, aggregate totals)
leaves the machine — never account numbers, IBANs, balances, or your identity.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Any, Iterable

from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings, get_settings
from ..models import Category, Rule, Transaction
from ..services.analytics import month_bounds, period_summary, spend_by_category
from ..services.categorise import recategorise_all

logger = logging.getLogger(__name__)

# The fallback beta lets Anthropic re-run a request on another model if the
# safety classifiers decline it, instead of handing us back a refusal.
FALLBACK_BETA = "server-side-fallback-2026-07-01"


class AIUnavailable(RuntimeError):
    """Raised when AI features are asked for but not configured."""


class AIError(RuntimeError):
    """A call to Claude failed in a way the user should hear about."""


def ai_status(settings: Settings | None = None) -> dict[str, Any]:
    settings = settings or get_settings()
    return {
        "enabled": bool(settings.anthropic_api_key),
        "model": settings.ai_model,
        "effort": settings.ai_effort,
        "reason": None if settings.anthropic_api_key else "ANTHROPIC_API_KEY is not set in .env",
    }


def get_client(settings: Settings | None = None):
    settings = settings or get_settings()
    if not settings.anthropic_api_key:
        raise AIUnavailable(
            "AI features are off. Add ANTHROPIC_API_KEY to your .env to turn them on."
        )
    import anthropic

    return anthropic.Anthropic(api_key=settings.anthropic_api_key, timeout=120.0)


# Models that reject `output_config.effort`. Haiku and the 4.5-era Sonnet never
# had the parameter, and the older Opus releases predate it. Anything not listed
# is assumed to support it, so a newer model works without a code change.
MODELS_WITHOUT_EFFORT = {
    "claude-sonnet-4-5",
    "claude-sonnet-4-0",
    "claude-opus-4-1",
    "claude-opus-4-0",
}

# Server-side refusal fallback only applies to the models whose safety
# classifiers can decline a request. Sending it elsewhere is rejected.
MODELS_WITH_FALLBACK_PREFIXES = ("claude-opus-5", "claude-fable-", "claude-mythos-")


def supports_effort(model: str) -> bool:
    return not model.startswith("claude-haiku") and model not in MODELS_WITHOUT_EFFORT


def supports_server_side_fallback(model: str) -> bool:
    return any(model.startswith(prefix) for prefix in MODELS_WITH_FALLBACK_PREFIXES)


def effort_kwargs(settings: Settings, effort: str | None = None) -> dict[str, Any]:
    """Effort setting, omitted entirely on models that would 400 on it."""
    if not supports_effort(settings.ai_model):
        return {}
    return {"output_config": {"effort": effort or settings.ai_effort}}


def beta_kwargs(settings: Settings) -> dict[str, Any]:
    """Server-side refusal fallback, so a declined request still gets answered."""
    if not settings.ai_server_side_fallback:
        return {}
    if not supports_server_side_fallback(settings.ai_model):
        return {}
    return {"betas": [FALLBACK_BETA], "fallbacks": "default"}


def request_kwargs(settings: Settings, effort: str | None = None) -> dict[str, Any]:
    """Everything model-dependent about a request, in one place."""
    return {**effort_kwargs(settings, effort), **beta_kwargs(settings)}


def call_with_fallback_retry(make_request, settings: Settings, effort: str | None = None):
    """Run a request, shedding optional parameters if the API rejects them.

    The capability tables above should prevent this, but a model whose support
    differs from what we assume shouldn't cost the user their question — so a
    rejected optional parameter is dropped and the request retried once.
    """
    import anthropic

    attempts = [request_kwargs(settings, effort)]
    without_fallback = effort_kwargs(settings, effort)
    if without_fallback != attempts[0]:
        attempts.append(without_fallback)
    if without_fallback:
        attempts.append({})

    last_error: Exception | None = None
    for index, extra in enumerate(attempts):
        try:
            return make_request(extra)
        except anthropic.BadRequestError as exc:
            message = str(exc).lower()
            optional_rejected = any(
                token in message
                for token in ("fallback", "beta", "effort", "output_config")
            )
            if not optional_rejected or index == len(attempts) - 1:
                raise
            logger.warning("Optional parameter rejected, retrying without it: %s", exc)
            last_error = exc
    raise last_error if last_error else RuntimeError("unreachable")


def friendly_error(exc: Exception) -> AIError:
    """Turn an SDK exception into something worth showing a person."""
    import anthropic

    if isinstance(exc, anthropic.AuthenticationError):
        return AIError(
            "Anthropic rejected the API key. Check ANTHROPIC_API_KEY in your .env "
            "and restart the app."
        )
    if isinstance(exc, anthropic.PermissionDeniedError):
        return AIError("That API key isn't allowed to use this model.")
    if isinstance(exc, anthropic.RateLimitError):
        return AIError("Rate limited by Anthropic. Wait a moment and ask again.")
    if isinstance(exc, anthropic.APIConnectionError):
        return AIError("Couldn't reach Anthropic — check this machine's internet connection.")
    if isinstance(exc, anthropic.APIStatusError) and exc.status_code >= 500:
        return AIError("Anthropic had a server error. Try again shortly.")
    return AIError(str(exc))


def text_of(response) -> str:
    """Pull the text out of a response, refusing to read content on a refusal."""
    if getattr(response, "stop_reason", None) == "refusal":
        details = getattr(response, "stop_details", None)
        category = getattr(details, "category", None) if details else None
        raise AIError(
            "Claude declined to answer that"
            + (f" (category: {category})" if category else "")
            + ". Try rephrasing the question."
        )
    parts = [block.text for block in response.content if block.type == "text"]
    return "\n".join(parts).strip()


# --------------------------------------------------------------------------
# Merchant categorisation
# --------------------------------------------------------------------------
class MerchantVerdict(BaseModel):
    merchant: str = Field(description="The merchant string exactly as it was given to you.")
    category: str = Field(description="One of the allowed category names, copied exactly.")
    confidence: float = Field(ge=0, le=1, description="0 to 1. Below 0.6 means a guess.")
    clean_name: str = Field(description="A short human-readable name, e.g. 'Tesco'.")


class MerchantVerdicts(BaseModel):
    verdicts: list[MerchantVerdict]


CATEGORISE_SYSTEM = """You categorise bank transaction descriptions for a personal \
finance app. You receive raw merchant strings exactly as a bank prints them — often \
abbreviated, with store numbers, city names, or payment-processor noise attached.

For each string, pick the single best category from the allowed list and give a clean \
display name. Rules:
- Use only category names from the allowed list, copied character for character.
- Judge by what the merchant sells, not by the wording of the description.
- A transfer between the person's own accounts, or to a savings or investment \
platform, is a transfer — not spending.
- If you genuinely cannot tell what a merchant is, use "Uncategorised" and set a low \
confidence. Guessing wrongly is worse than admitting you don't know.
- Confidence reflects how sure you are about the merchant's identity and category."""


@dataclass(slots=True)
class CategorisationResult:
    merchants_reviewed: int
    rules_created: int
    transactions_recategorised: int
    skipped_low_confidence: int
    verdicts: list[dict[str, Any]]

    def as_dict(self) -> dict[str, Any]:
        return {
            "merchants_reviewed": self.merchants_reviewed,
            "rules_created": self.rules_created,
            "transactions_recategorised": self.transactions_recategorised,
            "skipped_low_confidence": self.skipped_low_confidence,
            "verdicts": self.verdicts,
        }


def _uncategorised_merchants(db: Session, user_id: int, limit: int) -> list[tuple[str, int]]:
    """Distinct merchant strings that the deterministic layers couldn't place."""
    uncategorised = db.scalar(
        select(Category).where(Category.user_id == user_id, Category.name == "Uncategorised")
    )
    query = select(Transaction).where(
        Transaction.user_id == user_id,
        Transaction.category_locked.is_(False),
        Transaction.is_transfer.is_(False),
    )
    if uncategorised:
        query = query.where(
            (Transaction.category_id == uncategorised.id) | (Transaction.category_id.is_(None))
        )
    else:
        query = query.where(Transaction.category_id.is_(None))

    counts: dict[str, int] = {}
    for txn in db.scalars(query).all():
        label = (txn.merchant or txn.description or "").strip()
        if len(label) < 3:
            continue
        counts[label] = counts.get(label, 0) + 1

    ranked = sorted(counts.items(), key=lambda item: item[1], reverse=True)
    return ranked[:limit]


def categorise_uncategorised(
    db: Session,
    user_id: int,
    limit: int = 60,
    min_confidence: float = 0.6,
    settings: Settings | None = None,
) -> CategorisationResult:
    """Ask Claude to name the merchants we couldn't place, and save what it learns.

    Each accepted verdict becomes an ordinary categorisation rule, so the same
    merchant never costs a second API call and you can inspect, edit or delete
    what the model decided.
    """
    settings = settings or get_settings()
    client = get_client(settings)

    merchants = _uncategorised_merchants(db, user_id, limit)
    if not merchants:
        return CategorisationResult(0, 0, 0, 0, [])

    categories = {
        category.name: category
        for category in db.scalars(select(Category).where(Category.user_id == user_id)).all()
    }
    allowed = sorted(categories)

    listing = "\n".join(f"- {name} ({count} transactions)" for name, count in merchants)
    prompt = (
        f"Allowed categories:\n{chr(10).join('- ' + name for name in allowed)}\n\n"
        f"Merchant strings to categorise:\n{listing}\n\n"
        "Return one verdict per merchant string, echoing the merchant back exactly."
    )

    def make_request(extra: dict[str, Any]):
        return client.beta.messages.parse(
            model=settings.ai_model,
            max_tokens=16000,
            system=CATEGORISE_SYSTEM,
            output_format=MerchantVerdicts,
            messages=[{"role": "user", "content": prompt}],
            **extra,
        )

    try:
        response = call_with_fallback_retry(
            make_request, settings, effort=settings.ai_categorise_effort
        )
    except AIUnavailable:
        raise
    except Exception as exc:  # noqa: BLE001 - surfaced to the caller as a 502
        raise friendly_error(exc) from exc

    if getattr(response, "stop_reason", None) == "refusal":
        raise AIError("Claude declined to categorise those merchants.")

    parsed: MerchantVerdicts | None = getattr(response, "parsed_output", None)
    if parsed is None:
        raise AIError("Claude returned no structured output for categorisation.")

    known = {name for name, _ in merchants}
    created = 0
    skipped = 0
    recorded: list[dict[str, Any]] = []

    for verdict in parsed.verdicts:
        if verdict.merchant not in known:
            continue  # the model invented a merchant we never sent
        category = categories.get(verdict.category)
        if category is None or verdict.category == "Uncategorised":
            skipped += 1
            continue
        if verdict.confidence < min_confidence:
            skipped += 1
            recorded.append(
                {
                    "merchant": verdict.merchant,
                    "category": verdict.category,
                    "confidence": verdict.confidence,
                    "applied": False,
                }
            )
            continue

        existing = db.scalar(
            select(Rule).where(
                Rule.user_id == user_id,
                Rule.pattern == verdict.merchant,
                Rule.field == "description",
            )
        )
        if existing is None:
            db.add(
                Rule(
                    user_id=user_id,
                    name=f"AI: {verdict.clean_name or verdict.merchant}"[:120],
                    field="description",
                    match_type="contains",
                    pattern=verdict.merchant[:240],
                    category_id=category.id,
                    set_merchant=(verdict.clean_name or None),
                    priority=200,  # below hand-written rules, above keyword guesses
                    is_active=True,
                )
            )
            created += 1
        recorded.append(
            {
                "merchant": verdict.merchant,
                "clean_name": verdict.clean_name,
                "category": verdict.category,
                "confidence": verdict.confidence,
                "applied": True,
            }
        )

    db.commit()
    updated = recategorise_all(db, user_id, include_locked=False) if created else 0

    return CategorisationResult(
        merchants_reviewed=len(merchants),
        rules_created=created,
        transactions_recategorised=updated,
        skipped_low_confidence=skipped,
        verdicts=recorded,
    )


# --------------------------------------------------------------------------
# Written monthly insight
# --------------------------------------------------------------------------
INSIGHT_SYSTEM = """You write a short monthly money briefing for one person, from \
their own spending figures.

Write 3 to 5 sentences of plain prose. No headings, no bullet lists, no preamble.
Lead with the single most useful observation. Quote specific figures — a claim \
without a number is filler. Be direct and neutral: you are a competent accountant \
giving a quick read, not a coach. Never moralise about what they bought, never \
suggest budgeting products, and never invent a number that is not in the data. If \
the data is too thin to say anything useful, say exactly that in one sentence."""


def _fmt(amount_minor: int, currency: str) -> str:
    symbol = {"GBP": "£", "EUR": "€", "USD": "$"}.get(currency, f"{currency} ")
    return f"{symbol}{amount_minor / 100:,.2f}"


def monthly_briefing(
    db: Session,
    user_id: int,
    base_currency: str,
    anchor: date | None = None,
    settings: Settings | None = None,
) -> str:
    """A few sentences on how this month is going, written from the real figures."""
    settings = settings or get_settings()
    client = get_client(settings)

    anchor = anchor or date.today()
    this_start, this_end = month_bounds(anchor)
    from datetime import timedelta

    last_start, last_end = month_bounds(this_start - timedelta(days=1))

    this_month = period_summary(db, user_id, this_start, this_end, base_currency)
    last_month = period_summary(db, user_id, last_start, last_end, base_currency)
    this_categories = spend_by_category(db, user_id, this_start, this_end, base_currency)[:8]
    last_categories = {
        row["name"]: row["amount_minor"]
        for row in spend_by_category(db, user_id, last_start, last_end, base_currency)
    }

    if this_month["transaction_count"] < 3:
        return "Not enough activity this month yet to say anything useful."

    lines = [
        f"Currency: {base_currency}",
        f"This month so far ({this_start} to {anchor}):",
        f"  money in {_fmt(this_month['income_minor'], base_currency)}",
        f"  money out {_fmt(this_month['expense_minor'], base_currency)}",
        f"  net {_fmt(this_month['net_minor'], base_currency)}",
        f"  daily average spend {_fmt(this_month['average_daily_spend_minor'], base_currency)}",
        f"  projected full-month spend {_fmt(this_month['projected_month_spend_minor'], base_currency)}",
        f"Last month in full: in {_fmt(last_month['income_minor'], base_currency)}, "
        f"out {_fmt(last_month['expense_minor'], base_currency)}",
        "Top categories this month (with last month for comparison):",
    ]
    for row in this_categories:
        previous = last_categories.get(row["name"], 0)
        lines.append(
            f"  {row['name']}: {_fmt(row['amount_minor'], base_currency)} "
            f"(last month {_fmt(previous, base_currency)})"
        )

    def make_request(extra: dict[str, Any]):
        return client.beta.messages.create(
            model=settings.ai_model,
            max_tokens=16000,
            system=INSIGHT_SYSTEM,
            messages=[{"role": "user", "content": "\n".join(lines)}],
            **extra,
        )

    try:
        response = call_with_fallback_retry(make_request, settings)
    except AIUnavailable:
        raise
    except Exception as exc:  # noqa: BLE001
        raise friendly_error(exc) from exc

    return text_of(response)


def merchant_labels(transactions: Iterable[Transaction]) -> list[str]:
    """Distinct merchant labels, used by the categoriser and by tests."""
    seen: dict[str, None] = {}
    for txn in transactions:
        label = (txn.merchant or txn.description or "").strip()
        if len(label) >= 3:
            seen.setdefault(label, None)
    return list(seen)
