package com.singularity.todo.feature.reminders.domain.port

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
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

    /**
     * Every reminder, across every profile.
     *
     * ## Why a repository scoped to one profile can offer this
     *
     * Arming OS alarms is a device-wide operation. A phone that reboots must re-arm every
     * reminder it holds, not only the ones belonging to whichever profile happened to be
     * active when it came back — otherwise a reminder for a second profile silently never
     * fires until its owner switches to it and relaunches.
     *
     * Reads only, and the single sanctioned hole in profile isolation for this table.
     * See `CrossProfileReadRegistry`.
     */
    fun observeAllProfiles(): Flow<List<Reminder>>

    /** Single reminder observation by [id] for the current user. */
    fun observe(id: ReminderId): Flow<Reminder?>

    /** Get a reminder by [id] for the current user. */
    suspend fun get(id: ReminderId): Reminder?

    /** Creates or updates a reminder. Returns the saved reminder. */
    suspend fun upsert(reminder: Reminder): Result<Unit>

    /** Deletes a reminder by [id]. */
    suspend fun delete(id: ReminderId): Result<Unit>

    /** Deletes a reminder by [id] for an explicit [userId]. Used by [AlarmReceiver] to ensure cross-profile correctness. */
    suspend fun delete(id: ReminderId, userId: UserId): Result<Unit>

    // ─── Domain methods ───────────────────────────────────────────────────────

    /** IDs of tasks that have at least one recurring reminder. */
    fun observeRecurringTaskIds(): Flow<Set<TaskId>>

    /** Reminders for a specific task. */
    fun watchByTask(taskId: TaskId): Flow<List<Reminder>>

    /** Reminders due before [nowEpochMs] for the current user. */
    fun watchDueBefore(nowEpochMs: Long): Flow<List<Reminder>>

    /**
     * Most-recent [limit] reminders due before [nowEpochMs] for the current user.
     * Used by [AlarmReceiver] to catch up past-due reminders on boot without loading all rows.
     */
    fun watchRecentDueBefore(nowEpochMs: Long, limit: Int): Flow<List<Reminder>>

    /** Deletes all reminders for a task. */
    suspend fun deleteByTask(taskId: TaskId): Result<Unit>

    /**
     * Records that a reminder was successfully fired at [lastFiredAt].
     * Used by [ReminderScheduler] to prevent re-firing a recurring reminder too soon.
     */
    suspend fun markFired(reminderId: ReminderId, lastFiredAt: Long): Result<Unit>
}
