package com.financialmanager.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A record of something the assistant changed.
 *
 * The assistant writes to the user's finances without stopping to ask, which is
 * what makes it useful to talk to. The cost of that is a wrong edit landing
 * silently, so every change is written down in plain language and carries the
 * instructions to reverse it. Undo is the safety net that a confirmation prompt
 * would otherwise be, without putting a question in front of every sentence.
 */
@Entity(tableName = "changes")
data class ChangeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What happened, as the user would describe it. */
    val summary: String,
    /** How to put it back: an action name and its arguments, as JSON. */
    val inverse: String,
    val at: Long = System.currentTimeMillis(),
    val undone: Boolean = false,
)

@Dao
interface ChangeDao {

    @Insert
    suspend fun record(change: ChangeRecord): Long

    @Query("SELECT * FROM changes WHERE undone = 0 ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int = 10): Flow<List<ChangeRecord>>

    @Query("SELECT * FROM changes WHERE id = :id")
    suspend fun byId(id: Long): ChangeRecord?

    @Query("UPDATE changes SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("DELETE FROM changes")
    suspend fun clear()
}
