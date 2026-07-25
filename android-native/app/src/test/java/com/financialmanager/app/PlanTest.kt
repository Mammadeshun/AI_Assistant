package com.financialmanager.app

import com.financialmanager.app.data.Budget
import com.financialmanager.app.data.CategoryTotal
import com.financialmanager.app.data.Commitment
import com.financialmanager.app.data.CommitmentKind
import com.financialmanager.app.data.DetectedRecurring
import com.financialmanager.app.data.RecurringDetector
import com.financialmanager.app.data.TransactionRow
import com.financialmanager.app.plan.Planner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanTest {

    private fun commitment(
        name: String,
        amount: Long,
        day: Int? = null,
        remaining: Long? = null,
        kind: CommitmentKind = CommitmentKind.BILL,
    ) = Commitment(
        name = name, kind = kind, amountMinor = amount, currency = "EUR",
        dayOfMonth = day, remainingMinor = remaining,
    )

    @Test
    fun `safe to spend subtracts only what has not left yet`() {
        // On the 10th, rent taken on the 1st is already out of the balance;
        // subtracting it again would understate what is available by a month's
        // rent, every month.
        val today = LocalDate.of(2026, 3, 10)
        val plan = Planner.monthPlan(
            currency = "EUR",
            balanceMinor = 60_000,
            incomeMinor = 150_000,
            spentMinor = 40_000,
            commitments = listOf(
                commitment("Rent", 45_000, day = 1),
                commitment("Phone", 999, day = 20),
            ),
            totalOwedMinor = 0,
            today = today,
        )

        assertEquals(45_999, plan.committedMinor)
        assertEquals(999, plan.stillToLeaveMinor)     // only the phone bill is ahead
        assertEquals(59_001, plan.safeToSpendMinor)
    }

    @Test
    fun `a commitment with no known day is treated as still to come`() {
        // Erring toward caution: better to under-promise what is spendable.
        val plan = Planner.monthPlan(
            "EUR", balanceMinor = 10_000, incomeMinor = 0, spentMinor = 0,
            commitments = listOf(commitment("Loan", 5_000, day = null)),
            totalOwedMinor = 0, today = LocalDate.of(2026, 3, 10),
        )
        assertEquals(5_000, plan.safeToSpendMinor)
    }

    @Test
    fun `being short is flagged rather than shown as a positive number`() {
        val plan = Planner.monthPlan(
            "EUR", balanceMinor = 10_000, incomeMinor = 100_000, spentMinor = 0,
            commitments = listOf(commitment("Rent", 45_000, day = 28)),
            totalOwedMinor = 0, today = LocalDate.of(2026, 3, 10),
        )
        assertTrue(plan.isOverstretched)
        assertEquals(-35_000, plan.safeToSpendMinor)
    }

    @Test
    fun `commitments in another currency are left out`() {
        // Nothing is ever converted, so a GBP debt cannot be subtracted from a
        // EUR balance.
        val plan = Planner.monthPlan(
            "EUR", balanceMinor = 10_000, incomeMinor = 0, spentMinor = 0,
            commitments = listOf(
                commitment("Rent", 5_000, day = 28),
                Commitment(
                    name = "UK loan", kind = CommitmentKind.DEBT, amountMinor = 90_000,
                    currency = "GBP", dayOfMonth = 28,
                ),
            ),
            totalOwedMinor = 0, today = LocalDate.of(2026, 3, 10),
        )
        assertEquals(5_000, plan.committedMinor)
        assertEquals(5_000, plan.safeToSpendMinor)
    }

    @Test
    fun `daily allowance divides what is left by the days remaining`() {
        val plan = Planner.monthPlan(
            "EUR", balanceMinor = 31_000, incomeMinor = 0, spentMinor = 0,
            commitments = emptyList(), totalOwedMinor = 0,
            today = LocalDate.of(2026, 3, 21),   // 11 days left including today
        )
        assertEquals(11, plan.daysLeft)
        assertEquals(2_818, plan.dailyAllowanceMinor)
    }

    @Test
    fun `a debt knows when it will be paid off`() {
        val loan = commitment("Car loan", 20_000, day = 5, remaining = 100_000)
        assertEquals(5, loan.monthsRemaining)
        assertEquals(LocalDate.of(2026, 8, 10), loan.payoffDate(LocalDate.of(2026, 3, 10)))
    }

    @Test
    fun `a part month still counts as a payment`() {
        // 3 payments of 200 do not clear 500; it takes 3, not 2.5.
        assertEquals(3, commitment("X", 20_000, remaining = 50_000).monthsRemaining)
    }

    @Test
    fun `budget pace warns early in the month but not late`() {
        val budgets = listOf(Budget("Groceries", 40_000, "EUR"))
        val progress = Planner.budgetProgress(
            budgets, listOf(CategoryTotal("Groceries", "EUR", 24_000)),
        ).single()

        assertEquals(0.6f, progress.fraction, 0.001f)
        assertTrue(progress.isAheadOfPace(LocalDate.of(2026, 3, 5)))     // 60% by the 5th
        assertFalse(progress.isAheadOfPace(LocalDate.of(2026, 3, 25)))   // 60% by the 25th
        assertFalse(progress.isOver)
    }

    @Test
    fun `a budget that is blown is over rather than merely ahead`() {
        val progress = Planner.budgetProgress(
            listOf(Budget("Shopping", 10_000, "EUR")),
            listOf(CategoryTotal("Shopping", "EUR", 15_000)),
        ).single()
        assertTrue(progress.isOver)
        assertEquals(-5_000, progress.remainingMinor)
        assertFalse(progress.isAheadOfPace(LocalDate.of(2026, 3, 5)))
    }

    /* --- what is about to be taken ------------------------------------ */

    @Test
    fun `a payment later this month is due this month`() {
        val due = Planner
            .upcoming(listOf(commitment("Rent", 50_000, day = 20)), today = LocalDate.of(2026, 3, 4))
            .single()

        assertEquals(LocalDate.of(2026, 3, 20), due.due)
        assertEquals(16, due.daysAway)
        assertFalse(due.isImminent)
    }

    @Test
    fun `a payment whose day has passed rolls to next month`() {
        val due = Planner
            .upcoming(listOf(commitment("Rent", 50_000, day = 3)), today = LocalDate.of(2026, 3, 20))
            .single()

        assertEquals(LocalDate.of(2026, 4, 3), due.due)
    }

    @Test
    fun `today counts as due today rather than a month away`() {
        val due = Planner
            .upcoming(listOf(commitment("Rent", 50_000, day = 9)), today = LocalDate.of(2026, 3, 9))
            .single()

        assertEquals(0, due.daysAway)
        assertEquals("Today", due.whenText)
        assertTrue(due.isImminent)
    }

    @Test
    fun `the 31st lands on the last day of a short month`() {
        // February has no 31st. Clamping rather than throwing is the whole point.
        val due = Planner
            .upcoming(listOf(commitment("Card", 9_900, day = 31)), today = LocalDate.of(2026, 2, 5))
            .single()

        assertEquals(LocalDate.of(2026, 2, 28), due.due)
    }

    @Test
    fun `a commitment with no day recorded is left out rather than guessed`() {
        assertTrue(
            Planner.upcoming(
                listOf(commitment("Netflix", 1_299)), today = LocalDate.of(2026, 3, 4),
            ).isEmpty()
        )
    }

    @Test
    fun `paused commitments are not coming up`() {
        val paused = commitment("Gym", 3_000, day = 10).copy(active = false)
        assertTrue(Planner.upcoming(listOf(paused), today = LocalDate.of(2026, 3, 4)).isEmpty())
    }

    /* --- income --------------------------------------------------------- */

    @Test
    fun `income is not counted as something committed`() {
        val plan = Planner.monthPlan(
            currency = "EUR",
            balanceMinor = 100_000,
            incomeMinor = 0,
            spentMinor = 0,
            commitments = listOf(
                commitment("Rent", 50_000, day = 1),
                commitment("Revenue", 80_000, day = 10, kind = CommitmentKind.INCOME),
            ),
            totalOwedMinor = 0,
            today = LocalDate.of(2026, 3, 4),
        )

        assertEquals(50_000, plan.committedMinor)
    }

    @Test
    fun `income still to arrive does not raise what is safe to spend`() {
        // The whole point of the figure. €800 landing on the 10th is not €800
        // you can spend on the 4th, and a number that says otherwise is worse
        // than no number.
        val plan = Planner.monthPlan(
            currency = "EUR",
            balanceMinor = 20_000,
            incomeMinor = 0,
            spentMinor = 0,
            commitments = listOf(
                commitment("Revenue", 80_000, day = 10, kind = CommitmentKind.INCOME),
            ),
            totalOwedMinor = 0,
            today = LocalDate.of(2026, 3, 4),
        )

        assertEquals(20_000, plan.safeToSpendMinor)
        assertEquals(80_000, plan.expectedIncomeMinor)
    }

    @Test
    fun `income whose day has passed is already in the balance`() {
        val plan = Planner.monthPlan(
            currency = "EUR",
            balanceMinor = 100_000,
            incomeMinor = 0,
            spentMinor = 0,
            commitments = listOf(
                commitment("Revenue", 80_000, day = 10, kind = CommitmentKind.INCOME),
            ),
            totalOwedMinor = 0,
            today = LocalDate.of(2026, 3, 20),
        )

        assertEquals(0, plan.expectedIncomeMinor)
    }

    @Test
    fun `income appears in what is coming up, marked as coming in`() {
        val due = Planner.upcoming(
            listOf(
                commitment("Rent", 50_000, day = 6),
                commitment("Revenue", 80_000, day = 10, kind = CommitmentKind.INCOME),
            ),
            today = LocalDate.of(2026, 3, 4),
        )

        assertEquals(listOf("Rent", "Revenue"), due.map { it.commitment.name })
        assertFalse(due[0].isIncome)
        assertTrue(due[1].isIncome)
    }

    @Test
    fun `soonest first`() {
        val due = Planner.upcoming(
            listOf(
                commitment("Late", 100, day = 28),
                commitment("Soon", 100, day = 6),
                commitment("Middle", 100, day = 15),
            ),
            today = LocalDate.of(2026, 3, 4),
        )
        assertEquals(listOf("Soon", "Middle", "Late"), due.map { it.commitment.name })
    }
}

class RecurringDetectorTest {

    private var nextId = 1L

    private fun txn(merchant: String, date: LocalDate, amountMinor: Long) = TransactionRow(
        id = nextId++, externalId = "x${nextId}", bookedAt = date, amountMinor = amountMinor,
        currency = "EUR", description = merchant, merchant = merchant, category = "Uncategorised",
    )

    @Test
    fun `a monthly subscription is found`() {
        val rows = (0..5).map {
            txn("Spotify", LocalDate.of(2026, 1, 12).plusMonths(it.toLong()), -1099)
        }
        val found = RecurringDetector.detect(rows).single()

        assertEquals("Spotify", found.merchant)
        assertEquals(1099, found.typicalAmountMinor)
        assertEquals(6, found.occurrences)
        assertEquals(12, found.dayOfMonth)
    }

    @Test
    fun `a supermarket is not a subscription`() {
        // Frequent, but irregular and for wildly different amounts.
        val rows = listOf(
            txn("Esselunga", LocalDate.of(2026, 1, 3), -1245),
            txn("Esselunga", LocalDate.of(2026, 1, 9), -3310),
            txn("Esselunga", LocalDate.of(2026, 1, 11), -820),
            txn("Esselunga", LocalDate.of(2026, 2, 2), -6605),
            txn("Esselunga", LocalDate.of(2026, 2, 19), -1190),
        )
        assertTrue(RecurringDetector.detect(rows).isEmpty())
    }

    @Test
    fun `two payments are not enough to call it a pattern`() {
        val rows = listOf(
            txn("Netflix", LocalDate.of(2026, 1, 8), -1399),
            txn("Netflix", LocalDate.of(2026, 2, 8), -1399),
        )
        assertTrue(RecurringDetector.detect(rows).isEmpty())
    }

    @Test
    fun `a price rise within reason is still the same subscription`() {
        val rows = listOf(
            txn("iliad", LocalDate.of(2026, 1, 5), -999),
            txn("iliad", LocalDate.of(2026, 2, 5), -999),
            txn("iliad", LocalDate.of(2026, 3, 5), -1099),
            txn("iliad", LocalDate.of(2026, 4, 5), -1099),
        )
        assertEquals(1, RecurringDetector.detect(rows).size)
    }

    @Test
    fun `money coming in is never a commitment`() {
        val rows = (0..4).map {
            txn("Salary", LocalDate.of(2026, 1, 28).plusMonths(it.toLong()), 150_000)
        }
        assertTrue(RecurringDetector.detect(rows).isEmpty())
    }

    @Test
    fun `yearly cost is estimated from the cycle`() {
        val rows = (0..3).map {
            txn("Gym", LocalDate.of(2026, 1, 15).plusMonths(it.toLong()), -3000)
        }
        val found: DetectedRecurring = RecurringDetector.detect(rows).single()
        // Roughly twelve payments a year, give or take the length of a month.
        assertTrue(found.yearlyMinor.toString(), found.yearlyMinor in 33_000..39_000)
    }
}
