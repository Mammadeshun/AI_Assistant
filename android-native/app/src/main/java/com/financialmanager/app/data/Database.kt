package com.financialmanager.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(
    tableName = "transactions",
    indices = [
        // The uniqueness that makes re-importing a statement idempotent.
        Index(value = ["externalId"], unique = true),
        Index(value = ["bookedAt"]),
    ],
)
data class TransactionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalId: String,
    val bookedAt: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val description: String,
    val merchant: String,
    val category: String,
    val isTransfer: Boolean = false,
    /** True once the user picks a category by hand — never overwritten after that. */
    val categoryLocked: Boolean = false,
    val dateEstimated: Boolean = false,
)

class Converters {
    @TypeConverter
    fun dateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun kindToString(value: CommitmentKind?): String? = value?.name

    @TypeConverter
    fun stringToKind(value: String?): CommitmentKind? =
        value?.let { name -> CommitmentKind.entries.firstOrNull { it.name == name } }
}

/** A row of the "spent per category" breakdown. */
data class CategoryTotal(val category: String, val currency: String, val totalMinor: Long)

/** A row of the month-by-month cash flow. */
data class MonthTotal(val month: String, val incomeMinor: Long, val expenseMinor: Long)

data class MerchantTotal(val merchant: String, val totalMinor: Long, val count: Int)

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TransactionRow>): List<Long>

    @Query("SELECT COUNT(*) FROM transactions")
    fun count(): Flow<Int>

    @Query("SELECT * FROM transactions ORDER BY bookedAt DESC, id DESC LIMIT :limit OFFSET :offset")
    fun page(limit: Int, offset: Int): Flow<List<TransactionRow>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE (:query = '' OR merchant LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
          AND (:category IS NULL OR category = :category)
        ORDER BY bookedAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun search(query: String, category: String?, limit: Int, offset: Int): Flow<List<TransactionRow>>

    @Query("UPDATE transactions SET category = :category, categoryLocked = 1 WHERE id = :id")
    suspend fun setCategory(id: Long, category: String)

    @Query("SELECT DISTINCT currency FROM transactions")
    suspend fun currencies(): List<String>

    @Query("SELECT MIN(bookedAt) FROM transactions")
    suspend fun earliest(): LocalDate?

    @Query("SELECT MAX(bookedAt) FROM transactions")
    suspend fun latest(): LocalDate?

    /** Transfers are excluded: moving your own money is not income or spending. */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE currency = :currency AND isTransfer = 0 AND amountMinor > 0
          AND bookedAt BETWEEN :start AND :end
        """
    )
    fun incomeBetween(currency: String, start: LocalDate, end: LocalDate): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(-amountMinor), 0) FROM transactions
        WHERE currency = :currency AND isTransfer = 0 AND amountMinor < 0
          AND bookedAt BETWEEN :start AND :end
        """
    )
    fun spendBetween(currency: String, start: LocalDate, end: LocalDate): Flow<Long>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE currency = :currency")
    fun balance(currency: String): Flow<Long>

    @Query(
        """
        SELECT category, currency, SUM(-amountMinor) AS totalMinor FROM transactions
        WHERE currency = :currency AND isTransfer = 0 AND amountMinor < 0
          AND bookedAt BETWEEN :start AND :end
        GROUP BY category, currency
        ORDER BY totalMinor DESC
        """
    )
    fun spendByCategory(currency: String, start: LocalDate, end: LocalDate): Flow<List<CategoryTotal>>

    @Query(
        """
        SELECT merchant, SUM(-amountMinor) AS totalMinor, COUNT(*) AS count FROM transactions
        WHERE currency = :currency AND isTransfer = 0 AND amountMinor < 0
          AND bookedAt BETWEEN :start AND :end
        GROUP BY merchant
        ORDER BY totalMinor DESC
        LIMIT :limit
        """
    )
    fun topMerchants(
        currency: String, start: LocalDate, end: LocalDate, limit: Int,
    ): Flow<List<MerchantTotal>>

    @Query(
        """
        SELECT substr(bookedAt, 1, 7) AS month,
               COALESCE(SUM(CASE WHEN amountMinor > 0 THEN amountMinor ELSE 0 END), 0) AS incomeMinor,
               COALESCE(SUM(CASE WHEN amountMinor < 0 THEN -amountMinor ELSE 0 END), 0) AS expenseMinor
        FROM transactions
        WHERE currency = :currency AND isTransfer = 0 AND bookedAt >= :since
        GROUP BY month
        ORDER BY month
        """
    )
    fun monthlyTotals(currency: String, since: LocalDate): Flow<List<MonthTotal>>

    /** Used by the recurring-payment detector, which needs the rows themselves. */
    @Query("SELECT * FROM transactions WHERE bookedAt >= :since ORDER BY bookedAt")
    suspend fun since(since: LocalDate): List<TransactionRow>

    @Query(
        """
        SELECT * FROM transactions
        WHERE merchant LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'
        ORDER BY bookedAt DESC LIMIT 500
        """
    )
    suspend fun searchNow(query: String): List<TransactionRow>

    @Query("DELETE FROM transactions WHERE externalId = :externalId")
    suspend fun deleteByExternalId(externalId: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Database(
    entities = [
        TransactionRow::class, Commitment::class, Budget::class,
        CashHolding::class, ChangeRecord::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun commitments(): CommitmentDao
    abstract fun budgets(): BudgetDao
    abstract fun cash(): CashDao
    abstract fun changes(): ChangeDao

    companion object {
        @Volatile private var instance: FinanceDatabase? = null

        fun get(context: Context): FinanceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, FinanceDatabase::class.java, "finance.db",
            )
                // The only schema change so far adds tables, and the alternative
                // to rebuilding is shipping a migration for a version nobody has
                // data in. Transactions can be re-imported from the statement.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
