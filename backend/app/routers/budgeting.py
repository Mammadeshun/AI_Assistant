"""Categories, categorisation rules and budgets."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import current_user
from ..models import Budget, Category, CategoryKind, Rule, Transaction, User
from ..schemas import (
    BudgetOut,
    BudgetUpsert,
    CategoryCreate,
    CategoryOut,
    RuleCreate,
    RuleOut,
)
from ..services.categorise import ensure_default_categories

router = APIRouter(prefix="/api", tags=["budgeting"])


# --- categories -----------------------------------------------------------
@router.get("/categories", response_model=list[CategoryOut])
def list_categories(
    user: User = Depends(current_user), db: Session = Depends(get_db)
) -> list[Category]:
    ensure_default_categories(db, user.id)
    db.commit()
    return list(
        db.scalars(
            select(Category).where(Category.user_id == user.id).order_by(Category.name)
        ).all()
    )


@router.post("/categories", response_model=CategoryOut, status_code=status.HTTP_201_CREATED)
def create_category(
    payload: CategoryCreate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Category:
    exists = db.scalar(
        select(Category).where(Category.user_id == user.id, Category.name == payload.name)
    )
    if exists:
        raise HTTPException(status.HTTP_409_CONFLICT, "A category with that name already exists.")

    category = Category(
        user_id=user.id,
        name=payload.name,
        kind=CategoryKind(payload.kind),
        colour=payload.colour,
        icon=payload.icon,
        is_system=False,
    )
    db.add(category)
    db.commit()
    db.refresh(category)
    return category


@router.delete("/categories/{category_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def delete_category(
    category_id: int,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> None:
    category = db.get(Category, category_id)
    if category is None or category.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Category not found")
    if category.is_system:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Built-in categories cannot be deleted.")

    # Transactions keep their history; they just fall back to uncategorised.
    db.query(Transaction).filter(
        Transaction.user_id == user.id, Transaction.category_id == category_id
    ).update({Transaction.category_id: None, Transaction.category_locked: False})
    db.delete(category)
    db.commit()


# --- rules ----------------------------------------------------------------
@router.get("/rules", response_model=list[RuleOut])
def list_rules(user: User = Depends(current_user), db: Session = Depends(get_db)) -> list[Rule]:
    return list(
        db.scalars(
            select(Rule).where(Rule.user_id == user.id).order_by(Rule.priority, Rule.id)
        ).all()
    )


@router.post("/rules", response_model=RuleOut, status_code=status.HTTP_201_CREATED)
def create_rule(
    payload: RuleCreate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Rule:
    if payload.match_type == "regex":
        import re

        try:
            re.compile(payload.pattern)
        except re.error as exc:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, f"Invalid regex: {exc}") from exc

    if payload.category_id is not None:
        category = db.get(Category, payload.category_id)
        if category is None or category.user_id != user.id:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category")

    rule = Rule(user_id=user.id, **payload.model_dump())
    db.add(rule)
    db.commit()
    db.refresh(rule)
    return rule


@router.delete("/rules/{rule_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def delete_rule(
    rule_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> None:
    rule = db.get(Rule, rule_id)
    if rule is None or rule.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Rule not found")
    db.delete(rule)
    db.commit()


# --- budgets --------------------------------------------------------------
@router.get("/budgets", response_model=list[BudgetOut])
def list_budgets(user: User = Depends(current_user), db: Session = Depends(get_db)) -> list[Budget]:
    return list(db.scalars(select(Budget).where(Budget.user_id == user.id)).all())


@router.put("/budgets", response_model=BudgetOut)
def upsert_budget(
    payload: BudgetUpsert,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> Budget:
    category = db.get(Category, payload.category_id)
    if category is None or category.user_id != user.id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category")

    budget = db.scalar(
        select(Budget).where(Budget.user_id == user.id, Budget.category_id == payload.category_id)
    )
    if budget is None:
        budget = Budget(user_id=user.id, category_id=payload.category_id)
        db.add(budget)

    budget.amount_minor = payload.amount_minor
    budget.currency = payload.currency
    budget.period = payload.period
    db.commit()
    db.refresh(budget)
    return budget


@router.delete("/budgets/{budget_id}", status_code=status.HTTP_204_NO_CONTENT, response_model=None)
def delete_budget(
    budget_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)
) -> None:
    budget = db.get(Budget, budget_id)
    if budget is None or budget.user_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Budget not found")
    db.delete(budget)
    db.commit()
