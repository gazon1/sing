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
    @ColumnInfo("calendar_id") val calendarId: String,
    @ColumnInfo("event_id") val eventId: Long,
    @ColumnInfo("synced_at") val syncedAt: Long,
    /** Stable hash of the event fields at sync time. Used by [SyncDiffMerge] to skip re-writes. */
    @ColumnInfo("checksum") val checksum: Int = 0,
)

/**
 * DAO for [CalendarSyncTaskMapEntity].
 */
@Dao
interface CalendarSyncTaskMapDao {

    /** Observe the entire map as a Flow. */
    @Query("SELECT * FROM calendar_sync_task_map")
    fun observeAll(): Flow<List<CalendarSyncTaskMapEntity>>

    /** Get all mappings as a snapshot (non-Flow). */
    @Query("SELECT * FROM calendar_sync_task_map")
    suspend fun getAll(): List<CalendarSyncTaskMapEntity>

    /** Get the event ID for a specific task, if any. */
    @Query("SELECT event_id FROM calendar_sync_task_map WHERE task_id = :taskId")
    suspend fun getEventId(taskId: String): Long?

    /** Get the full mapping entity for a specific task, if any. */
    @Query("SELECT * FROM calendar_sync_task_map WHERE task_id = :taskId")
    suspend fun getByTaskId(taskId: String): CalendarSyncTaskMapEntity?

    /** Insert or replace a mapping. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalendarSyncTaskMapEntity)

    /** Delete the mapping for a specific task. */
    @Query("DELETE FROM calendar_sync_task_map WHERE task_id = :taskId")
    suspend fun delete(taskId: String)

    /** Delete the mapping for a specific system calendar event ID. */
    @Query("DELETE FROM calendar_sync_task_map WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: Long)

    /** Delete all mappings for tasks not in the given set. */
    @Query("DELETE FROM calendar_sync_task_map WHERE task_id NOT IN (:taskIds)")
    suspend fun deleteStale(taskIds: List<String>)

    /** Clear all mappings. */
    @Query("DELETE FROM calendar_sync_task_map")
    suspend fun clearAll()
}
