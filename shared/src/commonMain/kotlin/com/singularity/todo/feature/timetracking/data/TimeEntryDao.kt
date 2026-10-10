package com.singularity.todo.feature.timetracking.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import com.singularity.todo.core.database.SyncColumns
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [TimeEntryEntity] operations.
 *
 * All mutations are scoped to a user via `user_id`.
 * The open entry is identified by `ended_at IS NULL`.
 */
@Dao
interface TimeEntryDao {
    // ── Scoped reads ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM time_entries WHERE task_id = :taskId AND deleted_at IS NULL ORDER BY started_at DESC")
    fun watchForTask(taskId: String): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE user_id = :userId AND ended_at IS NULL AND deleted_at IS NULL LIMIT 1")
    fun watchOpenEntry(userId: String): Flow<TimeEntryEntity?>

    @Query("SELECT * FROM time_entries WHERE user_id = :userId AND ended_at IS NULL AND deleted_at IS NULL LIMIT 1")
    suspend fun getOpenEntry(userId: String): TimeEntryEntity?

    @Query(
        """
        SELECT * FROM time_entries
        WHERE user_id = :userId
          AND started_at >= :startMs
          AND started_at < :endMs
          AND deleted_at IS NULL
        ORDER BY started_at DESC
        """,
    )
    fun watchForUserInRange(userId: String, startMs: Long, endMs: Long): Flow<List<TimeEntryEntity>>

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Upsert
    suspend fun upsert(entity: TimeEntryEntity)

    @Query("UPDATE time_entries SET ended_at = :endedAt, updated_at = :updatedAt WHERE id = :id AND user_id = :userId")
    suspend fun stopEntry(id: String, endedAt: Long, updatedAt: Long, userId: String): Int

    @Query("UPDATE time_entries SET note = :note, updated_at = :updatedAt WHERE id = :id AND user_id = :userId")
    suspend fun updateNote(id: String, note: String?, updatedAt: Long, userId: String): Int

    @Query(
        "UPDATE time_entries SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id AND user_id = :userId",
    )
    suspend fun softDelete(id: String, deletedAt: Long, userId: String): Int

    @Query("DELETE FROM time_entries WHERE id = :id AND user_id = :userId")
    suspend fun delete(id: String, userId: String): Int
}

/**
 * Room entity for time tracking entries.
 * All timestamp columns use epoch millis.
 */
@Entity(
    tableName = "time_entries",
    indices = [
        Index("task_id"),
        Index("started_at"),
        Index("user_id"),
    ],
)
data class TimeEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("started_at") val startedAt: Long,
    @ColumnInfo("ended_at") val endedAt: Long?,
    @ColumnInfo("kind") val kind: String,
    @ColumnInfo("source") val source: String,
    @ColumnInfo("note") val note: String?,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("deleted_at") val deletedAt: Long? = null,
    @Embedded val sync: SyncColumns = SyncColumns(),
)
