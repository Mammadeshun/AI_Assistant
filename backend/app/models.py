"""SQLAlchemy models.

Money is stored as integer minor units (pence/cents) to avoid float drift.
Every amount carries its own currency; conversion happens at read time only.
"""
from __future__ import annotations

import enum
from datetime import date, datetime, timezone

from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    Enum,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class ConnectionStatus(str, enum.Enum):
    pending = "pending"          # awaiting user authorisation
    active = "active"
    expired = "expired"          # consent/refresh token no longer valid
    error = "error"
    revoked = "revoked"


class CategoryKind(str, enum.Enum):
    expense = "expense"
    income = "income"
    transfer = "transfer"


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    display_name: Mapped[str | None] = mapped_column(String(120))
    base_currency: Mapped[str] = mapped_column(String(3), default="GBP")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    sessions: Mapped[list["UserSession"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class UserSession(Base):
    __tablename__ = "user_sessions"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(128), unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    user_agent: Mapped[str | None] = mapped_column(String(255))

    user: Mapped[User] = relationship(back_populates="sessions")


class Connection(Base):
    """A link to one institution through one provider."""

    __tablename__ = "connections"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    provider: Mapped[str] = mapped_column(String(50), index=True)
    label: Mapped[str] = mapped_column(String(120))
    institution_id: Mapped[str | None] = mapped_column(String(120))
    external_id: Mapped[str | None] = mapped_column(String(120))  # e.g. requisition id
    status: Mapped[ConnectionStatus] = mapped_column(
        Enum(ConnectionStatus), default=ConnectionStatus.pending
    )
    status_detail: Mapped[str | None] = mapped_column(Text)
    credentials: Mapped[str | None] = mapped_column(Text)  # Fernet-encrypted JSON
    consent_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    accounts: Mapped[list["Account"]] = relationship(
        back_populates="connection", cascade="all, delete-orphan"
    )


class Account(Base):
    __tablename__ = "accounts"
    __table_args__ = (UniqueConstraint("connection_id", "external_id", name="uq_account_external"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    connection_id: Mapped[int | None] = mapped_column(
        ForeignKey("connections.id", ondelete="CASCADE"), index=True
    )
    external_id: Mapped[str] = mapped_column(String(120))
    name: Mapped[str] = mapped_column(String(160))
    account_type: Mapped[str | None] = mapped_column(String(60))
    currency: Mapped[str] = mapped_column(String(3))
    iban_last4: Mapped[str | None] = mapped_column(String(8))
    balance_minor: Mapped[int] = mapped_column(Integer, default=0)
    available_minor: Mapped[int | None] = mapped_column(Integer)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    include_in_net_worth: Mapped[bool] = mapped_column(Boolean, default=True)
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    connection: Mapped[Connection | None] = relationship(back_populates="accounts")
    transactions: Mapped[list["Transaction"]] = relationship(
        back_populates="account", cascade="all, delete-orphan"
    )


class Category(Base):
    __tablename__ = "categories"
    __table_args__ = (UniqueConstraint("user_id", "name", name="uq_category_name"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(80))
    kind: Mapped[CategoryKind] = mapped_column(Enum(CategoryKind), default=CategoryKind.expense)
    colour: Mapped[str] = mapped_column(String(9), default="#8b8b8b")
    icon: Mapped[str | None] = mapped_column(String(16))
    is_system: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Transaction(Base):
    __tablename__ = "transactions"
    __table_args__ = (
        UniqueConstraint("account_id", "dedupe_key", name="uq_txn_dedupe"),
        Index("ix_txn_account_date", "account_id", "booked_at"),
        Index("ix_txn_user_date", "user_id", "booked_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id", ondelete="CASCADE"), index=True)
    category_id: Mapped[int | None] = mapped_column(
        ForeignKey("categories.id", ondelete="SET NULL"), index=True
    )

    external_id: Mapped[str | None] = mapped_column(String(160))
    dedupe_key: Mapped[str] = mapped_column(String(64))

    booked_at: Mapped[date] = mapped_column(Date, index=True)
    value_date: Mapped[date | None] = mapped_column(Date)
    amount_minor: Mapped[int] = mapped_column(Integer)  # negative = money out
    currency: Mapped[str] = mapped_column(String(3))

    description: Mapped[str] = mapped_column(String(400), default="")
    merchant: Mapped[str | None] = mapped_column(String(200), index=True)
    counterparty: Mapped[str | None] = mapped_column(String(200))
    reference: Mapped[str | None] = mapped_column(String(200))
    provider_category: Mapped[str | None] = mapped_column(String(80))
    state: Mapped[str] = mapped_column(String(30), default="completed")

    is_transfer: Mapped[bool] = mapped_column(Boolean, default=False)
    is_recurring: Mapped[bool] = mapped_column(Boolean, default=False)
    notes: Mapped[str | None] = mapped_column(Text)
    category_locked: Mapped[bool] = mapped_column(Boolean, default=False)  # user set it by hand

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    account: Mapped[Account] = relationship(back_populates="transactions")
    category: Mapped[Category | None] = relationship()


class Rule(Base):
    """User-defined categorisation rule, applied in priority order."""

    __tablename__ = "rules"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(120))
    field: Mapped[str] = mapped_column(String(30), default="description")  # description|merchant|counterparty|reference
    match_type: Mapped[str] = mapped_column(String(20), default="contains")  # contains|equals|regex|starts_with
    pattern: Mapped[str] = mapped_column(String(240))
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id", ondelete="CASCADE"))
    set_merchant: Mapped[str | None] = mapped_column(String(160))
    mark_transfer: Mapped[bool] = mapped_column(Boolean, default=False)
    priority: Mapped[int] = mapped_column(Integer, default=100)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Budget(Base):
    __tablename__ = "budgets"
    __table_args__ = (UniqueConstraint("user_id", "category_id", name="uq_budget_category"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id", ondelete="CASCADE"))
    amount_minor: Mapped[int] = mapped_column(Integer)
    currency: Mapped[str] = mapped_column(String(3), default="GBP")
    period: Mapped[str] = mapped_column(String(20), default="monthly")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    category: Mapped[Category] = relationship()


class SyncLog(Base):
    __tablename__ = "sync_logs"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    connection_id: Mapped[int | None] = mapped_column(ForeignKey("connections.id", ondelete="CASCADE"))
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(String(20), default="running")
    accounts_synced: Mapped[int] = mapped_column(Integer, default=0)
    transactions_added: Mapped[int] = mapped_column(Integer, default=0)
    transactions_updated: Mapped[int] = mapped_column(Integer, default=0)
    message: Mapped[str | None] = mapped_column(Text)


class OAuthState(Base):
    """Short-lived CSRF state for provider authorisation redirects."""

    __tablename__ = "oauth_states"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    state: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    provider: Mapped[str] = mapped_column(String(50))
    connection_id: Mapped[int | None] = mapped_column(ForeignKey("connections.id", ondelete="CASCADE"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
