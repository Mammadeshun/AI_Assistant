"""Automatic syncing: what's due, what isn't, and what must never be retried."""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock

import pytest

from backend.app.config import get_settings
from backend.app.models import Connection, ConnectionStatus
from backend.app.services import scheduler


def _connection(status=ConnectionStatus.active, hours_ago: float | None = None) -> Connection:
    last = None
    if hours_ago is not None:
        last = datetime.now(timezone.utc) - timedelta(hours=hours_ago)
    return Connection(user_id=1, provider="gocardless", label="Revolut", status=status, last_synced_at=last)


# --------------------------------------------------------------------------
# Which connections are due
# --------------------------------------------------------------------------
def test_never_synced_is_due():
    assert scheduler.is_due(_connection(hours_ago=None), interval_hours=12) is True


def test_recently_synced_is_not_due():
    assert scheduler.is_due(_connection(hours_ago=1), interval_hours=12) is False


def test_stale_connection_is_due():
    assert scheduler.is_due(_connection(hours_ago=13), interval_hours=12) is True


def test_exactly_at_the_interval_is_due():
    assert scheduler.is_due(_connection(hours_ago=12), interval_hours=12) is True


def test_naive_timestamp_is_treated_as_utc():
    """SQLite hands back naive datetimes; comparing them raw would raise."""
    connection = _connection(hours_ago=None)
    connection.last_synced_at = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(hours=20)
    assert scheduler.is_due(connection, interval_hours=12) is True


def test_expired_consent_is_never_auto_retried():
    """Only the user can fix an expired consent — retrying just burns quota."""
    stale = _connection(status=ConnectionStatus.expired, hours_ago=100)
    assert scheduler.is_due(stale, interval_hours=12) is False


def test_revoked_and_pending_are_not_retried():
    for status in (ConnectionStatus.revoked, ConnectionStatus.pending):
        assert scheduler.is_due(_connection(status=status, hours_ago=100), interval_hours=12) is False


def test_errored_connection_is_retried():
    """A failed sync may well have been transient."""
    assert scheduler.is_due(_connection(status=ConnectionStatus.error, hours_ago=13), 12) is True


# --------------------------------------------------------------------------
# The sync pass
# --------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_run_due_syncs_only_touches_due_connections(db, user_id, monkeypatch):
    fresh = Connection(
        user_id=user_id, provider="gocardless", label="Fresh",
        status=ConnectionStatus.active, last_synced_at=datetime.now(timezone.utc),
    )
    stale = Connection(
        user_id=user_id, provider="gocardless", label="Stale",
        status=ConnectionStatus.active,
        last_synced_at=datetime.now(timezone.utc) - timedelta(days=2),
    )
    expired = Connection(
        user_id=user_id, provider="gocardless", label="Expired",
        status=ConnectionStatus.expired, last_synced_at=None,
    )
    db.add_all([fresh, stale, expired])
    db.commit()

    synced_labels = []

    async def fake_sync(session, connection, **kwargs):
        synced_labels.append(connection.label)
        result = AsyncMock()
        result.errors = []
        result.transactions_added = 3
        return result

    monkeypatch.setattr("backend.app.services.sync.sync_connection", fake_sync)

    count = await scheduler.run_due_syncs(get_settings())

    assert synced_labels == ["Stale"]
    assert count == 1


@pytest.mark.asyncio
async def test_one_failing_connection_does_not_stop_the_others(db, user_id, monkeypatch):
    for label in ("Broken", "Fine"):
        db.add(
            Connection(
                user_id=user_id, provider="gocardless", label=label,
                status=ConnectionStatus.active, last_synced_at=None,
            )
        )
    db.commit()

    attempted = []

    async def fake_sync(session, connection, **kwargs):
        attempted.append(connection.label)
        if connection.label == "Broken":
            raise RuntimeError("provider exploded")
        result = AsyncMock()
        result.errors = []
        result.transactions_added = 0
        return result

    monkeypatch.setattr("backend.app.services.sync.sync_connection", fake_sync)

    count = await scheduler.run_due_syncs(get_settings())

    assert sorted(attempted) == ["Broken", "Fine"]
    assert count == 1  # the failure isn't counted, but didn't abort the pass


@pytest.mark.asyncio
async def test_nothing_due_makes_no_calls(db, user_id, monkeypatch):
    called = False

    async def fake_sync(session, connection, **kwargs):
        nonlocal called
        called = True

    monkeypatch.setattr("backend.app.services.sync.sync_connection", fake_sync)
    assert await scheduler.run_due_syncs(get_settings()) == 0
    assert called is False


# --------------------------------------------------------------------------
# The loop's lifecycle
# --------------------------------------------------------------------------
def test_disabled_scheduler_starts_nothing():
    settings = get_settings().model_copy(update={"auto_sync_enabled": False})
    assert scheduler.start(settings) is None


@pytest.mark.asyncio
async def test_loop_stops_promptly_when_asked():
    """Shutdown must not wait out the sync interval."""
    settings = get_settings().model_copy(
        update={
            "auto_sync_enabled": True,
            "auto_sync_start_delay_seconds": 30,
            "auto_sync_check_seconds": 30,
        }
    )
    stop = asyncio.Event()
    task = asyncio.create_task(scheduler.auto_sync_loop(stop, settings))
    await asyncio.sleep(0.05)

    stop.set()
    await asyncio.wait_for(task, timeout=2)  # would hit 30s if the wait weren't interruptible
    assert task.done()


@pytest.mark.asyncio
async def test_stop_task_handles_no_scheduler():
    await scheduler.stop_task(None)  # must not raise when auto-sync is off
