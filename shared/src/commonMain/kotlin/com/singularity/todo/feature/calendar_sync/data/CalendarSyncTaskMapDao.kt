package com.singularity.todo.feature.calendar_sync.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stores the mapping from a local task ID to its corresponding system-calendar event ID.
 * One row per task. The [calendarId] field stores which Android calendar the event lives in.
 *
 * Mapped to [com.singularity.todo.feature.calendar_sync.domain.model.SyncedEventRef] for the
 * pure [com.singularity.todo.feature.calendar_sync.domain.logic.SyncDiffMerge] diff, which uses it
 * to determine whether a task is already synced and which system event ID to update/delete.
 */
@Entity(tableName = "calendar_sync_task_map")
data class CalendarSyncTaskMapEntity(
    @PrimaryKey @ColumnInfo("task_id") val taskId: String,
    /**
     * The user who owns this mapping. Nullable only for legacy rows created before
     * the multi-profile fix — they are cleaned up as stale on the next sync pass.
     */
    @ColumnInfo("user_id") val userId: String?,
    @ColumnInfo("calendar_id") val calendarId: String,
    @ColumnInfo("event_id") val eventId: Long,
    @ColumnInfo("synced_at") val syncedAt: Long,
    /** Stable hash of the event fields at sync time. Used by [SyncDiffMerge] to skip re-writes. */
    @ColumnInfo("checksum") val checksum: Int = 0,
)

/**
 * DAO for [CalendarSyncTaskMapEntity].
 *
 * All methods are scoped to [userId] for multi-profile safety.
 * The [deleteStale] method — called by [CalendarSyncWorker][com.singularity.todo.feature.calendar_sync.CalendarSyncWorker]
 * — MUST NOT delete rows belonging to other profiles, which is why [userId] is
 * required even for the stale-cleanup query.
 */
@Dao
interface CalendarSyncTaskMapDao {

    /** Observe all mappings for a given user. */
    @Query("SELECT * FROM calendar_sync_task_map WHERE user_id = :userId")
    fun observeAll(userId: String): Flow<List<CalendarSyncTaskMapEntity>>

    /** Get all mappings for a given user as a snapshot (non-Flow). */
    @Query("SELECT * FROM calendar_sync_task_map WHERE user_id = :userId")
    suspend fun getAll(userId: String): List<CalendarSyncTaskMapEntity>

    /** Get the event ID for a specific task, if any (task must belong to [userId]). */
    @Query("SELECT event_id FROM calendar_sync_task_map WHERE task_id = :taskId AND user_id = :userId")
    suspend fun getEventId(taskId: String, userId: String): Long?

    /** Get the full mapping entity for a specific task, if any (task must belong to [userId]). */
    @Query("SELECT * FROM calendar_sync_task_map WHERE task_id = :taskId AND user_id = :userId")
    suspend fun getByTaskId(taskId: String, userId: String): CalendarSyncTaskMapEntity?

    /** Insert or replace a mapping. Caller must populate [CalendarSyncTaskMapEntity.userId]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalendarSyncTaskMapEntity)

    /** Delete the mapping for a specific task. */
    @Query("DELETE FROM calendar_sync_task_map WHERE task_id = :taskId AND user_id = :userId")
    suspend fun delete(taskId: String, userId: String)

    /** Delete the mapping for a specific system calendar event ID. */
    @Query("DELETE FROM calendar_sync_task_map WHERE event_id = :eventId AND user_id = :userId")
    suspend fun deleteByEventId(eventId: Long, userId: String)

    /**
     * Delete all mappings for tasks not in the given set, scoped to [userId].
     *
     * WARNING: prior to the fix that added [userId] parameter, this query ran without
     * a user filter and would delete rows from ALL profiles on a multi-profile device.
     * Always pass [userId] — no exceptions.
     */
    @Query("DELETE FROM calendar_sync_task_map WHERE user_id = :userId AND task_id NOT IN (:taskIds)")
    suspend fun deleteStale(taskIds: List<String>, userId: String)

    /**
     * Delete all rows that have NULL [userId] — legacy rows from before the multi-profile
     * fix. These are cleaned up as stale on every sync pass.
     */
    @Query("DELETE FROM calendar_sync_task_map WHERE user_id IS NULL")
    suspend fun deleteLegacyRows()

    /** Clear all mappings for a given user. */
    @Query("DELETE FROM calendar_sync_task_map WHERE user_id = :userId")
    suspend fun clearAll(userId: String)
}
