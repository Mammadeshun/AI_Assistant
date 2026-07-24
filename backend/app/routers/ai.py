"""AI endpoints: finance chat, merchant categorisation, written briefing."""
from __future__ import annotations

import json
from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import Settings
from ..db import get_db
from ..deps import current_user, settings_dep
from ..models import ChatMessage, ChatThread, User, utcnow
from ..schemas import (
    AIStatus,
    BriefingResponse,
    CategoriseResponse,
    ChatMessageOut,
    ChatRequest,
    ChatResponse,
    ChatThreadOut,
)
from ..services import ai as ai_service
from ..services import ai_agent

router = APIRouter(prefix="/api/ai", tags=["ai"])


def _require_ai(settings: Settings) -> None:
    if not settings.anthropic_api_key:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "AI features are off. Add ANTHROPIC_API_KEY to your .env and restart.",
        )


@router.get("/status", response_model=AIStatus)
def status_endpoint(
    _: User = Depends(current_user), settings: Settings = Depends(settings_dep)
) -> AIStatus:
    return AIStatus(**ai_service.ai_status(settings))


# --------------------------------------------------------------------------
# Chat
# --------------------------------------------------------------------------
@router.get("/threads", response_model=list[ChatThreadOut])
def list_threads(
    user: User = Depends(current_user), db: Session = Depends(get_db)
) -> list[ChatThread]:
    return list(
        db.scalars(
            select(ChatThread)
            .where(ChatThread.user_id == user.id)
            .order_by(ChatThread.updated_at.desc())
            .limit(50)
        ).all()
    )


@router.get("/threads/{thread_id}", response_model=list[ChatMessageOut])
def thread_messages(
    thread_id: int,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> list[dict]:
    thread = db.get(ChatThread, thread_id)
    if thread is None or thread.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Conversation not found")
    return [
        {
            "id": message.id,
            "role": message.role,
            "content": message.content,
            "created_at": message.created_at,
            "tool_calls": json.loads(message.tool_calls) if message.tool_calls else [],
        }
        for message in thread.messages
    ]


@router.delete("/threads/{thread_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def delete_thread(
    thread_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> None:
    thread = db.get(ChatThread, thread_id)
    if thread is None or thread.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Conversation not found")
    db.delete(thread)
    db.commit()


@router.post("/chat", response_model=ChatResponse)
def chat(
    payload: ChatRequest,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> ChatResponse:
    _require_ai(settings)

    if payload.thread_id is None:
        thread = ChatThread(user_id=user.id, title=payload.message[:80])
        db.add(thread)
        db.commit()
        db.refresh(thread)
    else:
        thread = db.get(ChatThread, payload.thread_id)
        if thread is None or thread.user_id != user.id:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Conversation not found")

    history = [
        {"role": message.role, "content": message.content} for message in thread.messages
    ]
    history.append({"role": "user", "content": payload.message})
    history = ai_agent.trim_history(history, settings.ai_chat_history_turns)

    db.add(ChatMessage(thread_id=thread.id, role="user", content=payload.message))
    db.commit()

    try:
        result = ai_agent.chat(db, user, history, settings)
    except ai_service.AIUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
    except ai_service.AIError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc

    db.add(
        ChatMessage(
            thread_id=thread.id,
            role="assistant",
            content=result.reply,
            tool_calls=json.dumps([call.as_dict() for call in result.tool_calls]),
        )
    )
    thread.updated_at = utcnow()
    db.add(thread)
    db.commit()

    return ChatResponse(
        thread_id=thread.id,
        reply=result.reply,
        tool_calls=[call.as_dict() for call in result.tool_calls],
        usage={"input_tokens": result.input_tokens, "output_tokens": result.output_tokens},
    )


# --------------------------------------------------------------------------
# Categorisation and briefing
# --------------------------------------------------------------------------
@router.post("/categorise", response_model=CategoriseResponse)
def categorise(
    limit: int = Query(default=60, ge=1, le=200),
    min_confidence: float = Query(default=0.6, ge=0, le=1),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> CategoriseResponse:
    """Have Claude name the merchants the built-in rules couldn't place.

    Each accepted verdict is saved as an ordinary rule, so it costs one call per
    new merchant, never one per transaction — and you can edit or delete what it
    decided from the Rules tab.
    """
    _require_ai(settings)
    try:
        result = ai_service.categorise_uncategorised(
            db, user.id, limit=limit, min_confidence=min_confidence, settings=settings
        )
    except ai_service.AIUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
    except ai_service.AIError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
    return CategoriseResponse(**result.as_dict())


@router.post("/briefing", response_model=BriefingResponse)
def briefing(
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(settings_dep),
) -> BriefingResponse:
    """A few sentences on how this month is going, written from your own figures."""
    _require_ai(settings)
    try:
        text = ai_service.monthly_briefing(db, user.id, user.base_currency, settings=settings)
    except ai_service.AIUnavailable as exc:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(exc)) from exc
    except ai_service.AIError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from exc
    return BriefingResponse(text=text, generated_for=date.today())
