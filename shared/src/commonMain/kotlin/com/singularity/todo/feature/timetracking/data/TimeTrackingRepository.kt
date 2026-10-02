package com.singularity.todo.feature.timetracking.data

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import kotlinx.coroutines.flow.Flow

/**
 * Repository for time tracking entries.
 *
 * All methods that mutate data are scoped to the current user.
 * A single user can have at most one open (running) entry at any time.
 */
interface TimeTrackingRepository {
    /**
     * Watch all time entries for [taskId], ordered by [startedAt] descending.
     * Soft-deleted entries are excluded.
     */
    fun watchForTask(taskId: TaskId): Flow<List<TimeEntry>>

    /**
     * Watch the single open (running) time entry for [userId], if one exists.
     * Returns an empty flow if no entry is currently running.
     */
    fun watchOpenEntry(userId: UserId): Flow<TimeEntry?>

    /**
     * Get the open time entry for [userId], if any.
     * Returns null if no entry is running.
     */
    suspend fun getOpenEntry(userId: UserId): TimeEntry?

    /**
     * Start a new time entry for [taskId].
     *
     * Fails if [userId] already has an open entry.
     *
     * @return the id of the newly created entry.
     */
    suspend fun startEntry(
        taskId: TaskId,
        userId: UserId,
        kind: TimeEntryKind,
        source: TimeEntrySource,
    ): Result<TimeEntryId>

    /**
     * Stop the currently open entry for [userId], setting its [endedAt].
     *
     * No-op if [userId] has no open entry.
     *
     * @return the stopped entry, or null if there was no open entry.
     */
    suspend fun stopEntry(userId: UserId): Result<TimeEntry?>

    /**
     * Create a manual time entry with explicit start and end times.
     *
     * [startedAt] and [endedAt] are epoch millis.
     * [endedAt] must be after [startedAt].
     */
    suspend fun createManualEntry(
        taskId: TaskId,
        userId: UserId,
        startedAt: Long,
        endedAt: Long,
        kind: TimeEntryKind,
        note: String?,
    ): Result<TimeEntryId>

    /**
     * Update the note on an existing entry.
     */
    suspend fun updateNote(entryId: TimeEntryId, userId: UserId, note: String?): Result<Unit>

    /**
     * Soft-delete a time entry.
     */
    suspend fun delete(entryId: TimeEntryId): Result<Unit>

    /**
     * Watch all time entries for a user within a time range (for insights).
     * Returns entries ordered by [startedAt] descending.
     */
    fun watchForUserInRange(userId: UserId, startMs: Long, endMs: Long): Flow<List<TimeEntry>>
}
