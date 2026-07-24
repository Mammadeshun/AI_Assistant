"""Pydantic request/response models."""
from __future__ import annotations

from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator


class ORMModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# --- auth -----------------------------------------------------------------
class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=10, max_length=200)
    display_name: str | None = Field(default=None, max_length=120)
    base_currency: str = Field(default="GBP", min_length=3, max_length=3)

    @field_validator("base_currency")
    @classmethod
    def upper(cls, value: str) -> str:
        return value.upper()


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class UserOut(ORMModel):
    id: int
    email: str
    display_name: str | None
    base_currency: str


# --- accounts -------------------------------------------------------------
class AccountOut(ORMModel):
    id: int
    connection_id: int | None
    name: str
    account_type: str | None
    currency: str
    iban_last4: str | None
    balance_minor: int
    available_minor: int | None
    is_active: bool
    include_in_net_worth: bool
    last_synced_at: datetime | None


class AccountUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=160)
    is_active: bool | None = None
    include_in_net_worth: bool | None = None


class ManualAccountCreate(BaseModel):
    name: str = Field(max_length=160)
    currency: str = Field(default="GBP", min_length=3, max_length=3)
    account_type: str | None = Field(default="manual", max_length=60)
    opening_balance_minor: int = 0

    @field_validator("currency")
    @classmethod
    def upper(cls, value: str) -> str:
        return value.upper()


# --- connections ----------------------------------------------------------
class ConnectionOut(ORMModel):
    id: int
    provider: str
    label: str
    institution_id: str | None
    status: str
    status_detail: str | None
    consent_expires_at: datetime | None
    last_synced_at: datetime | None
    created_at: datetime

    @field_validator("status", mode="before")
    @classmethod
    def enum_to_str(cls, value: Any) -> str:
        return getattr(value, "value", value)


class StartLinkRequest(BaseModel):
    label: str | None = Field(default=None, max_length=120)
    country: str = Field(default="GB", min_length=2, max_length=2)
    institution_id: str | None = None

    @field_validator("country")
    @classmethod
    def upper(cls, value: str) -> str:
        return value.upper()


class StartLinkResponse(BaseModel):
    connection_id: int
    authorisation_url: str
    provider: str
    expires_in_seconds: int


# --- transactions ---------------------------------------------------------
class TransactionOut(ORMModel):
    id: int
    account_id: int
    category_id: int | None
    booked_at: date
    amount_minor: int
    currency: str
    description: str
    merchant: str | None
    counterparty: str | None
    reference: str | None
    state: str
    is_transfer: bool
    notes: str | None
    category_locked: bool


class TransactionPage(BaseModel):
    items: list[TransactionOut]
    total: int
    limit: int
    offset: int


class TransactionUpdate(BaseModel):
    category_id: int | None = None
    merchant: str | None = Field(default=None, max_length=200)
    notes: str | None = None
    is_transfer: bool | None = None


class BulkCategorise(BaseModel):
    transaction_ids: list[int] = Field(min_length=1, max_length=500)
    category_id: int | None = None


class ManualTransactionCreate(BaseModel):
    account_id: int
    booked_at: date
    amount_minor: int
    description: str = Field(max_length=400)
    category_id: int | None = None
    merchant: str | None = Field(default=None, max_length=200)
    notes: str | None = None
    is_transfer: bool = False


# --- categories, rules, budgets -------------------------------------------
class CategoryOut(ORMModel):
    id: int
    name: str
    kind: str
    colour: str
    icon: str | None
    is_system: bool

    @field_validator("kind", mode="before")
    @classmethod
    def enum_to_str(cls, value: Any) -> str:
        return getattr(value, "value", value)


class CategoryCreate(BaseModel):
    name: str = Field(max_length=80)
    kind: Literal["expense", "income", "transfer"] = "expense"
    colour: str = Field(default="#8b8b8b", max_length=9)
    icon: str | None = Field(default=None, max_length=16)


class RuleOut(ORMModel):
    id: int
    name: str
    field: str
    match_type: str
    pattern: str
    category_id: int | None
    set_merchant: str | None
    mark_transfer: bool
    priority: int
    is_active: bool


class RuleCreate(BaseModel):
    name: str = Field(max_length=120)
    field: Literal["description", "merchant", "counterparty", "reference"] = "description"
    match_type: Literal["contains", "equals", "regex", "starts_with"] = "contains"
    pattern: str = Field(min_length=1, max_length=240)
    category_id: int | None = None
    set_merchant: str | None = Field(default=None, max_length=160)
    mark_transfer: bool = False
    priority: int = 100
    is_active: bool = True


class BudgetOut(ORMModel):
    id: int
    category_id: int
    amount_minor: int
    currency: str
    period: str


class BudgetUpsert(BaseModel):
    category_id: int
    amount_minor: int = Field(ge=0)
    currency: str = Field(default="GBP", min_length=3, max_length=3)
    period: Literal["monthly"] = "monthly"

    @field_validator("currency")
    @classmethod
    def upper(cls, value: str) -> str:
        return value.upper()


# --- sync -----------------------------------------------------------------
class SyncResponse(BaseModel):
    ok: bool
    accounts_synced: int
    transactions_added: int
    transactions_updated: int
    errors: list[str]


class ImportResponse(BaseModel):
    account_id: int
    parsed: int
    added: int
    updated: int


# --- AI -------------------------------------------------------------------
class AIStatus(BaseModel):
    enabled: bool
    model: str
    effort: str
    reason: str | None = None


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    thread_id: int | None = None


class ChatToolCall(BaseModel):
    name: str
    input: dict[str, Any]


class ChatResponse(BaseModel):
    thread_id: int
    reply: str
    tool_calls: list[ChatToolCall]
    usage: dict[str, int]


class ChatMessageOut(ORMModel):
    id: int
    role: str
    content: str
    created_at: datetime
    tool_calls: list[ChatToolCall] = Field(default_factory=list)


class ChatThreadOut(ORMModel):
    id: int
    title: str
    created_at: datetime
    updated_at: datetime


class CategoriseResponse(BaseModel):
    merchants_reviewed: int
    rules_created: int
    transactions_recategorised: int
    skipped_low_confidence: int
    verdicts: list[dict[str, Any]]


class BriefingResponse(BaseModel):
    text: str
    generated_for: date
