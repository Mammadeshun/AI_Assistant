package com.financialmanager.app.plan

import com.financialmanager.app.data.Budget
import com.financialmanager.app.data.CategoryTotal
import com.financialmanager.app.data.Commitment
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * What is left of the month once the promises are taken out.
 *
 * A balance on its own is misleading. €600 in the account with €430 of rent and
 * instalments still to leave is not €600 of spending money, and the difference
 * between those two numbers is where overdrafts come from. Everything here
 * exists to turn the first number into the second.
 */
data class MonthPlan(
    val currency: String,
    val balanceMinor: Long,
    val incomeMinor: Long,
    val spentMinor: Long,
    /** Every active commitment, per month. */
    val committedMinor: Long,
    /** The part of that which has not left the account yet this month. */
    val stillToLeaveMinor: Long,
    val daysLeft: Int,
    val totalOwedMinor: Long,
    /**
     * Regular income still to arrive this month.
     *
     * Shown, never added to what is safe to spend. Money due on the 10th is not
     * money you have on the 4th, and a figure that counts it would tell you to
     * spend what has not turned up — which is exactly the mistake this app
     * exists to stop. It belongs in the sentence under the number, not in it.
     */
    val expectedIncomeMinor: Long = 0,
) {
    /** The headline: spend this and you can still pay what you owe this month. */
    val safeToSpendMinor: Long get() = balanceMinor - stillToLeaveMinor

    /** Spread evenly over what remains of the month. */
    val dailyAllowanceMinor: Long
        get() = if (daysLeft <= 0) safeToSpendMinor else safeToSpendMinor / daysLeft

    val isOverstretched: Boolean get() = safeToSpendMinor < 0

    /** Share of income already promised to someone else. */
    val committedShare: Float
        get() = if (incomeMinor <= 0) 0f else (committedMinor.toFloat() / incomeMinor).coerceIn(0f, 1f)
}

data class BudgetProgress(
    val category: String,
    val limitMinor: Long,
    val spentMinor: Long,
    val currency: String,
) {
    val fraction: Float
        get() = if (limitMinor <= 0) 0f else (spentMinor.toFloat() / limitMinor)

    val remainingMinor: Long get() = limitMinor - spentMinor
    val isOver: Boolean get() = spentMinor > limitMinor

    /**
     * True when spending is ahead of where the calendar says it should be — the
     * useful warning, because "60% spent" is fine on the 20th and alarming on
     * the 5th.
     */
    fun isAheadOfPace(today: LocalDate = LocalDate.now()): Boolean {
        val length = today.lengthOfMonth().toFloat()
        return fraction > (today.dayOfMonth / length) && !isOver
    }
}

object Planner {

    fun monthPlan(
        currency: String,
        balanceMinor: Long,
        incomeMinor: Long,
        spentMinor: Long,
        commitments: List<Commitment>,
        totalOwedMinor: Long,
        today: LocalDate = LocalDate.now(),
    ): MonthPlan {
        val mine = commitments.filter { it.active && it.currency == currency }
        val outgoing = mine.filterNot { it.kind.isIncome }
        val committed = outgoing.sumOf { it.amountMinor }

        // A payment whose day has passed has already come out of the balance, so
        // subtracting it again would double-count it. One with no known day is
        // treated as still to come, which errs toward caution.
        val stillToLeave = outgoing.filterNot { it.alreadyDue(today) }.sumOf { it.amountMinor }

        // The mirror image: income whose day has passed is already in the
        // balance. What is left is what is still to arrive.
        val expectedIncome = mine
            .filter { it.kind.isIncome && !it.alreadyDue(today) }
            .sumOf { it.amountMinor }

        return MonthPlan(
            expectedIncomeMinor = expectedIncome,
            currency = currency,
            balanceMinor = balanceMinor,
            incomeMinor = incomeMinor,
            spentMinor = spentMinor,
            committedMinor = committed,
            stillToLeaveMinor = stillToLeave,
            daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1,
            totalOwedMinor = totalOwedMinor,
        )
    }

    fun budgetProgress(
        budgets: List<Budget>,
        spend: List<CategoryTotal>,
    ): List<BudgetProgress> {
        val spentByCategory = spend.associate { it.category to it.totalMinor }
        return budgets.map { budget ->
            BudgetProgress(
                category = budget.category,
                limitMinor = budget.limitMinor,
                spentMinor = spentByCategory[budget.category] ?: 0L,
                currency = budget.currency,
            )
        }.sortedByDescending { it.fraction }
    }

    /**
     * What is about to be taken, soonest first.
     *
     * The most useful thing the app knows and the hardest to keep in your head:
     * not what you have spent, but what is already on its way out. A payment due
     * in three days is the reason a balance that looks fine is not.
     *
     * A commitment with no day recorded is left out rather than guessed at a
     * date — a wrong date here would be worse than a missing row.
     */
    fun upcoming(
        commitments: List<Commitment>,
        withinDays: Long = 45,
        today: LocalDate = LocalDate.now(),
    ): List<Upcoming> =
        commitments
            .filter { it.active && it.dayOfMonth != null }
            .mapNotNull { commitment ->
                val due = commitment.nextDue(today) ?: return@mapNotNull null
                val away = ChronoUnit.DAYS.between(today, due)
                if (away > withinDays) null else Upcoming(commitment, due, away.toInt())
            }
            .sortedBy { it.due }

    /**
     * When the debts and instalment plans finish, soonest first.
     *
     * Seeing "three more payments" against a number you have been paying for a
     * year is the thing that makes a debt feel finite.
     */
    fun payoffSchedule(
        commitments: List<Commitment>,
        today: LocalDate = LocalDate.now(),
    ): List<Pair<Commitment, LocalDate>> =
        commitments
            .filter { it.active && it.remainingMinor != null && it.amountMinor > 0 }
            .mapNotNull { commitment -> commitment.payoffDate(today)?.let { commitment to it } }
            .sortedBy { it.second }

    /**
     * A plain-language read on the month, worked out on the phone.
     *
     * Deliberately not an API call: it costs nothing, works offline, and is
     * right rather than plausible. The assistant is for the open-ended questions
     * this cannot answer.
     */
    fun observations(
        plan: MonthPlan,
        budgets: List<BudgetProgress>,
        commitments: List<Commitment>,
        lastMonthSpendMinor: Long,
        today: LocalDate = LocalDate.now(),
    ): List<Observation> {
        val notes = mutableListOf<Observation>()

        if (plan.isOverstretched) {
            notes += Observation(
                Severity.ALERT,
                "Not enough to cover what's still due",
                "€ is short by the amount below once this month's remaining payments leave.",
            )
        } else if (plan.daysLeft > 0 && plan.dailyAllowanceMinor >= 0) {
            notes += Observation(
                Severity.INFO,
                "Daily allowance",
                "Spending under this each day for the ${plan.daysLeft} days left keeps you clear.",
            )
        }

        if (plan.committedShare > 0.5f && plan.incomeMinor > 0) {
            notes += Observation(
                Severity.WARNING,
                "Over half your income is committed",
                "Fixed payments take ${(plan.committedShare * 100).toInt()}% before you spend anything.",
            )
        }

        budgets.filter { it.isOver }.forEach {
            notes += Observation(Severity.ALERT, "${it.category} is over budget", "")
        }
        budgets.filter { it.isAheadOfPace(today) }.take(2).forEach {
            notes += Observation(
                Severity.WARNING,
                "${it.category} is running ahead",
                "${(it.fraction * 100).toInt()}% spent, ${today.dayOfMonth} days into the month.",
            )
        }

        if (lastMonthSpendMinor > 0) {
            val change = (plan.spentMinor - lastMonthSpendMinor) * 100 / lastMonthSpendMinor
            if (change >= 20) {
                notes += Observation(
                    Severity.WARNING, "Spending is up $change% on last month", "",
                )
            } else if (change <= -20) {
                notes += Observation(
                    Severity.GOOD, "Spending is down ${-change}% on last month", "",
                )
            }
        }

        payoffSchedule(commitments, today).firstOrNull()?.let { (commitment, date) ->
            notes += Observation(
                Severity.GOOD,
                "${commitment.name} finishes ${YearMonth.from(date)}",
                "${commitment.monthsRemaining} payments left.",
            )
        }

        return notes
    }
}

/** A payment that has not happened yet, and how long there is until it does. */
data class Upcoming(
    val commitment: Commitment,
    val due: LocalDate,
    val daysAway: Int,
) {
    /** "Today", "Tomorrow", "in 5 days" — how anyone would actually say it. */
    val whenText: String
        get() = when (daysAway) {
            0 -> "Today"
            1 -> "Tomorrow"
            in 2..13 -> "in $daysAway days"
            else -> "${due.dayOfMonth} ${due.month.getDisplayName(
                java.time.format.TextStyle.SHORT, java.util.Locale.getDefault(),
            )}"
        }

    val isImminent: Boolean get() = daysAway <= 3

    /** Money arriving rather than leaving, which reads differently in a list. */
    val isIncome: Boolean get() = commitment.kind.isIncome
}

enum class Severity { ALERT, WARNING, INFO, GOOD }

data class Observation(val severity: Severity, val title: String, val detail: String)
