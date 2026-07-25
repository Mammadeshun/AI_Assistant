package com.financialmanager.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What kind of money-out this is. They behave differently: a debt shrinks as you
 * pay it, a subscription runs until you cancel it.
 */
enum class CommitmentKind(val label: String, val icon: String) {
    DEBT("Debt", "🏦"),
    INSTALMENT("Instalment plan", "🧾"),
    SUBSCRIPTION("Subscription", "🔁"),
    BILL("Bill", "💡"),
}

/**
 * Something the user has already promised to pay every month.
 *
 * These are the difference between "you have €600" and "you have €600 but €430
 * of it is already spoken for". Transactions cannot tell you this on their own:
 * a loan you took out last year is invisible in a statement until it takes the
 * money, which is too late to plan around.
 */
@Entity(tableName = "commitments")
data class Commitment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CommitmentKind,
    /** What leaves the account each month. */
    val amountMinor: Long,
    val currency: String,
    /** Day of the month it is taken, where known. */
    val dayOfMonth: Int? = null,
    /** For debts and instalment plans: what is still owed in total. */
    val remainingMinor: Long? = null,
    val note: String? = null,
    /** Suggested from the transaction history rather than typed in by hand. */
    val autoDetected: Boolean = false,
    val active: Boolean = true,
) {
    /** Months until this is paid off, when that is a knowable thing. */
    val monthsRemaining: Int?
        get() {
            val remaining = remainingMinor ?: return null
            if (amountMinor <= 0) return null
            return ((remaining + amountMinor - 1) / amountMinor).toInt()
        }

    /** The month this finishes, for debts and instalment plans. */
    fun payoffDate(from: LocalDate = LocalDate.now()): LocalDate? =
        monthsRemaining?.let { from.plusMonths(it.toLong()) }

    /**
     * Whether this month's payment has most likely already gone out.
     *
     * Based on the day of the month rather than on matching a transaction: a
     * direct debit that left on the 3rd is not worth subtracting again from
     * what is left to spend on the 20th.
     */
    fun alreadyDue(today: LocalDate = LocalDate.now()): Boolean {
        val day = dayOfMonth ?: return false
        return today.dayOfMonth >= day
    }

    /**
     * The next date this will be taken, for commitments with a known day.
     *
     * The day is clamped to the length of the month, so a payment on the 31st
     * lands on the 28th in February rather than throwing.
     */
    fun nextDue(today: LocalDate = LocalDate.now()): LocalDate? {
        val day = dayOfMonth ?: return null
        val thisMonth = today.withDayOfMonth(day.coerceAtMost(today.lengthOfMonth()))
        if (!thisMonth.isBefore(today)) return thisMonth
        val next = today.plusMonths(1)
        return next.withDayOfMonth(day.coerceAtMost(next.lengthOfMonth()))
    }
}

@Dao
interface CommitmentDao {

    @Query("SELECT * FROM commitments WHERE active = 1 ORDER BY kind, amountMinor DESC")
    fun active(): Flow<List<Commitment>>

    @Query("SELECT * FROM commitments ORDER BY active DESC, kind, amountMinor DESC")
    fun all(): Flow<List<Commitment>>

    @Query("SELECT * FROM commitments WHERE active = 1")
    suspend fun activeNow(): List<Commitment>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM commitments WHERE active = 1 AND currency = :currency")
    fun monthlyTotal(currency: String): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(remainingMinor), 0) FROM commitments
        WHERE active = 1 AND currency = :currency AND remainingMinor IS NOT NULL
        """
    )
    fun totalOwed(currency: String): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(commitment: Commitment): Long

    @Update
    suspend fun update(commitment: Commitment)

    @Delete
    suspend fun delete(commitment: Commitment)

    @Query("SELECT EXISTS(SELECT 1 FROM commitments WHERE LOWER(name) = LOWER(:name))")
    suspend fun exists(name: String): Boolean

    @Query("DELETE FROM commitments WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * A monthly spending limit for a category.
 */
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey val category: String,
    val limitMinor: Long,
    val currency: String,
)

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets")
    fun all(): Flow<List<Budget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: Budget)

    @Query("DELETE FROM budgets WHERE category = :category")
    suspend fun delete(category: String)
}

/**
 * A regular payment spotted in the transaction history.
 *
 * Worth surfacing because people forget what they are subscribed to, and
 * because these are exactly the commitments they would otherwise have to type
 * in by hand.
 */
data class DetectedRecurring(
    val merchant: String,
    val typicalAmountMinor: Long,
    val currency: String,
    val occurrences: Int,
    val averageGapDays: Int,
    val lastSeen: LocalDate,
    val dayOfMonth: Int,
) {
    val yearlyMinor: Long get() = typicalAmountMinor * (365 / averageGapDays.coerceAtLeast(1))
}

/**
 * Finds payments that repeat on a regular cycle.
 *
 * The rules are deliberately strict, because a wrong suggestion is worse than a
 * missing one: at least three payments, spaced consistently near a month (or a
 * week), to the same merchant, for an amount that barely moves. Groceries at the
 * same supermarket happen often but at irregular intervals and wildly varying
 * amounts, which is what keeps them out.
 */
object RecurringDetector {

    fun detect(transactions: List<TransactionRow>): List<DetectedRecurring> {
        val byMerchant = transactions
            .filter { it.amountMinor < 0 && !it.isTransfer }
            .groupBy { it.merchant.trim().lowercase() }

        return byMerchant.mapNotNull { (_, rows) ->
            if (rows.size < 3) return@mapNotNull null

            val sorted = rows.sortedBy { it.bookedAt }
            val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.bookedAt, b.bookedAt) }
                .filter { it > 0 }
            if (gaps.size < 2) return@mapNotNull null

            val averageGap = gaps.average()
            // Monthly, four-weekly or weekly. Anything else is just a shop the
            // user happens to like.
            val cycle = when {
                averageGap in 25.0..35.0 -> averageGap
                averageGap in 12.0..16.0 -> averageGap
                averageGap in 6.0..8.0 -> averageGap
                else -> return@mapNotNull null
            }

            // The spacing has to be consistent, not merely average out.
            val spread = gaps.maxOf { kotlin.math.abs(it - cycle) }
            if (spread > cycle * 0.4) return@mapNotNull null

            val amounts = sorted.map { -it.amountMinor }
            val typical = amounts.sorted()[amounts.size / 2]
            if (typical <= 0) return@mapNotNull null
            // A subscription's price barely moves; a supermarket bill does.
            if (amounts.any { kotlin.math.abs(it - typical) > typical * 0.2 }) return@mapNotNull null

            val last = sorted.last()
            DetectedRecurring(
                merchant = last.merchant,
                typicalAmountMinor = typical,
                currency = last.currency,
                occurrences = sorted.size,
                averageGapDays = cycle.toInt(),
                lastSeen = last.bookedAt,
                dayOfMonth = last.bookedAt.dayOfMonth,
            )
        }.sortedByDescending { it.yearlyMinor }
    }
}

/**
 * Money the user holds that no statement knows about.
 *
 * Cash in a wallet is real money and it is invisible to a bank export, so
 * without it the balance the app works from is wrong by however much is in
 * someone's pocket. Kept per currency, like everything else here.
 */
@Entity(tableName = "cash")
data class CashHolding(
    @PrimaryKey val currency: String,
    val amountMinor: Long,
    val updatedAt: LocalDate = LocalDate.now(),
)

@Dao
interface CashDao {
    @Query("SELECT * FROM cash WHERE currency = :currency")
    fun forCurrency(currency: String): Flow<CashHolding?>

    @Query("SELECT COALESCE(amountMinor, 0) FROM cash WHERE currency = :currency")
    suspend fun amountNow(currency: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(holding: CashHolding)

    @Query("DELETE FROM cash WHERE currency = :currency")
    suspend fun clear(currency: String)
}
