package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow

/**
 * Repository port for [Reminder] persistence.
 * Platform implementations: Room (Android/desktop) via [ReminderDao].
 */
interface ReminderRepository {

    // ─── Generic CRUD (ambient user) ─────────────────────────────────────────

    /** All reminders for the current user. */
    fun observeAll(): Flow<List<Reminder>>

    /** Single reminder observation by [id] for the current user. */
    fun observe(id: ReminderId): Flow<Reminder?>

    /** Get a reminder by [id] for the current user. */
    suspend fun get(id: ReminderId): Reminder?

    /** Creates or updates a reminder. Returns the saved reminder. */
    suspend fun upsert(reminder: Reminder): Result<Unit>

    /** Deletes a reminder by [id]. */
    suspend fun delete(id: ReminderId): Result<Unit>

    // ─── Domain methods ───────────────────────────────────────────────────────

    /** Reminders for a specific task. */
    fun watchByTask(taskId: TaskId): Flow<List<Reminder>>

    /** Reminders due before [nowEpochMs] for the current user. */
    fun watchDueBefore(nowEpochMs: Long): Flow<List<Reminder>>

    /** Deletes all reminders for a task. */
    suspend fun deleteByTask(taskId: TaskId): Result<Unit>

    // ─── Explicit userId overloads (kept for callers that pass userId explicitly) ──

    /** All reminders for a specific [userId]. */
    fun watchAll(userId: UserId): Flow<List<Reminder>>

    /** Reminders for a specific task and user. */
    fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>>

    /** Reminders due before [nowEpochMs] for a specific [userId]. */
    fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>>

    /** Delete a reminder by id (two-arg form). */
    suspend fun delete(id: ReminderId, userId: UserId): Result<Unit>

    /** Delete all reminders for a task (two-arg form). */
    suspend fun deleteByTask(taskId: TaskId, userId: UserId): Result<Unit>

    /** Get a reminder by id (two-arg form). */
    suspend fun getById(id: ReminderId, userId: UserId): Result<Reminder?>
}
