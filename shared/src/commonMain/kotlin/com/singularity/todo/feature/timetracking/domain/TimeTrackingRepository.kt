package com.singularity.todo.feature.timetracking.domain

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.TimeEntry
import com.singularity.todo.feature.timetracking.TimeEntryKind
import com.singularity.todo.feature.timetracking.TimeEntrySource
import kotlinx.coroutines.flow.Flow

/**
 * Port interface for time tracking operations.
 * The implementation lives in the data layer; this interface is in the domain layer
 * so domain-layer classes (like [com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps])
 * can reference it without violating the architecture rule that domain must not depend on data.
 */
interface TimeTrackingRepository {
    /**
     * Watch all time entries for [taskId], ordered by [startedAt] descending.
     * Soft-deleted entries are excluded.
     */
    fun watchEntries(taskId: TaskId): Flow<List<TimeEntry>>

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
    suspend fun startEntry(taskId: TaskId, userId: UserId, kind: TimeEntryKind, source: TimeEntrySource): Result<TimeEntryId>

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
}
