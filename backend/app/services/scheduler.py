"""Background auto-sync.

Keeps connected accounts up to date without anyone pressing a button. Runs as
one asyncio task inside the app process — no extra service, no cron entry, and
nothing to remember to start.

The interval defaults to 12 hours on purpose. GoCardless allows roughly four
reads per account per day on the production tier, and one sync spends one of
them, so twice a day leaves headroom for manual syncs without ever tripping the
limit.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from ..config import Settings, get_settings
from ..db import SessionLocal
from ..models import Connection, ConnectionStatus

logger = logging.getLogger(__name__)

# Statuses worth retrying automatically. `expired` is excluded deliberately:
# the consent is gone and only the user can restore it, so retrying would burn
# the daily allowance to no purpose.
SYNCABLE = (ConnectionStatus.active, ConnectionStatus.error)


def is_due(connection: Connection, interval_hours: int, now: datetime | None = None) -> bool:
    """Has it been long enough since this connection last synced?"""
    if connection.status not in SYNCABLE:
        return False
    if connection.last_synced_at is None:
        return True

    now = now or datetime.now(timezone.utc)
    last = connection.last_synced_at
    if last.tzinfo is None:
        last = last.replace(tzinfo=timezone.utc)
    return now - last >= timedelta(hours=interval_hours)


async def run_due_syncs(settings: Settings | None = None) -> int:
    """Sync every connection that's due. Returns how many were synced."""
    from .sync import sync_connection  # imported late to avoid a cycle

    settings = settings or get_settings()
    db = SessionLocal()
    synced = 0
    try:
        connections = db.scalars(
            select(Connection).where(Connection.status.in_(SYNCABLE))
        ).all()
        due = [c for c in connections if is_due(c, settings.auto_sync_interval_hours)]
        if not due:
            return 0

        logger.info("Auto-sync: %d connection(s) due", len(due))
        for connection in due:
            try:
                result = await sync_connection(db, connection)
                synced += 1
                if result.errors:
                    logger.warning("Auto-sync %s: %s", connection.label, "; ".join(result.errors))
                else:
                    logger.info(
                        "Auto-sync %s: %d new transaction(s)",
                        connection.label,
                        result.transactions_added,
                    )
            except Exception:  # noqa: BLE001 - one bad connection must not stop the rest
                logger.exception("Auto-sync failed for connection %s", connection.id)
    finally:
        db.close()
    return synced


async def auto_sync_loop(stop: asyncio.Event, settings: Settings | None = None) -> None:
    """Check for due connections forever, until asked to stop."""
    settings = settings or get_settings()

    # Let the app finish starting before doing any network work.
    try:
        await asyncio.wait_for(stop.wait(), timeout=settings.auto_sync_start_delay_seconds)
        return  # asked to stop during the delay
    except asyncio.TimeoutError:
        pass

    while not stop.is_set():
        try:
            await run_due_syncs(settings)
        except Exception:  # noqa: BLE001 - the loop must outlive any single failure
            logger.exception("Auto-sync pass failed")

        try:
            # Wake early if the app is shutting down, rather than blocking exit.
            await asyncio.wait_for(stop.wait(), timeout=settings.auto_sync_check_seconds)
        except asyncio.TimeoutError:
            continue


def start(settings: Settings | None = None) -> tuple[asyncio.Task, asyncio.Event] | None:
    """Start the loop, unless it's switched off."""
    settings = settings or get_settings()
    if not settings.auto_sync_enabled:
        logger.info("Auto-sync is disabled; connections will only sync when you ask.")
        return None

    stop = asyncio.Event()
    task = asyncio.create_task(auto_sync_loop(stop, settings))
    logger.info("Auto-sync every %d hours", settings.auto_sync_interval_hours)
    return task, stop


async def stop_task(handle: tuple[asyncio.Task, asyncio.Event] | None) -> None:
    if handle is None:
        return
    task, stop = handle
    stop.set()
    task.cancel()
    try:
        await task
    except (asyncio.CancelledError, Exception):  # noqa: BLE001 - shutdown is best effort
        pass
