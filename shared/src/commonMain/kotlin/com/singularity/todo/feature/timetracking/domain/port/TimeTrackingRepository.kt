package com.singularity.todo.feature.timetracking.domain.port

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
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
        source: TimeEntrySource = TimeEntrySource.Manual,
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
     * Writes a whole entry, replacing any row with the same id.
     *
     * Used **only** by the sync apply path, and deliberately shaped like the other
     * repositories' `upsert` rather than like the user-facing verbs above. Every other
     * method here is a command that enforces an invariant — [startEntry] refuses a second
     * open entry, [updateNote] is scoped to the caller, [delete] is a soft delete — and a
     * command taken from the server would be the wrong shape for that: an entry that
     * arrived from another device is already valid, and re-checking "does this user
     * already have one open" against it would reject a legitimate state change.
     *
     * The entry's own `userId` is honoured rather than overwritten with the local user.
     * The caller has already verified ownership — the pull path is scoped to the session's
     * owner — and rewriting it here would make the write disagree with the document that
     * was sent.
     */
    suspend fun upsert(entry: TimeEntry): Result<Unit>

    /**
     * Watch all time entries within the given time range, ordered by [startedAt] descending.
     * Scoped to the current user via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
     * Soft-deleted entries are excluded.
     */
    fun watchEntriesInRange(startMs: Long, endMs: Long): Flow<List<TimeEntry>>
}
