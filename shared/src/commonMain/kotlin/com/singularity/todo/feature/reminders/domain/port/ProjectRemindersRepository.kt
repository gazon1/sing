package com.singularity.todo.feature.reminders.domain.port

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Repository port for [com.singularity.todo.feature.reminders.ProjectReminder] persistence.
 *
 * Deliberately narrower than [ReminderRepository]: the model has no type, no offset
 * and no recurrence, so there is nothing to query on them. Every method here is scoped
 * to the current user, and every write asserts ownership before it lands.
 */
interface ProjectRemindersRepository {

    /** All project reminders for the current user, soonest first. */
    fun observeAll(): Flow<List<com.singularity.todo.feature.reminders.ProjectReminder>>

    /** Project reminders attached to [projectId], soonest first. */
    fun watchByProject(projectId: ProjectId): Flow<List<com.singularity.todo.feature.reminders.ProjectReminder>>

    /** Single reminder observation by [id] for the current user. */
    fun observe(
        id: com.singularity.todo.feature.reminders.ProjectReminderId,
    ): Flow<com.singularity.todo.feature.reminders.ProjectReminder?>

    /** Get a reminder by [id] for the current user. */
    suspend fun get(
        id: com.singularity.todo.feature.reminders.ProjectReminderId,
    ): com.singularity.todo.feature.reminders.ProjectReminder?

    /**
     * Creates or updates a reminder.
     *
     * Rejects when the caller is not the entity's owner, rather than silently
     * re-stamping the owner — see [com.singularity.todo.feature.profile.ProfileAwareCurrentUser.assertCanWrite].
     */
    suspend fun upsert(reminder: com.singularity.todo.feature.reminders.ProjectReminder): Result<Unit>

    /** Deletes a reminder by [id] for the current user. */
    suspend fun delete(id: com.singularity.todo.feature.reminders.ProjectReminderId): Result<Unit>

    /** Deletes a reminder by [id] for an explicit [userId]. Used by the fire receiver. */
    suspend fun delete(id: com.singularity.todo.feature.reminders.ProjectReminderId, userId: UserId): Result<Unit>

    /** Deletes every reminder attached to [projectId]. */
    suspend fun deleteByProject(projectId: ProjectId): Result<Unit>

    /** Project reminders due at or before [nowEpochMs], for the current user. */
    fun watchDueBefore(nowEpochMs: Long): Flow<List<com.singularity.todo.feature.reminders.ProjectReminder>>

    /**
     * Most-recent [limit] reminders due at or before [nowEpochMs].
     *
     * Backs the boot catch-up path, which must not load every past-due row.
     */
    fun watchRecentDueBefore(
        nowEpochMs: Long,
        limit: Int,
    ): Flow<List<com.singularity.todo.feature.reminders.ProjectReminder>>

    /** Records a successful fire, so a reboot does not re-fire the same reminder. */
    suspend fun markFired(
        reminderId: com.singularity.todo.feature.reminders.ProjectReminderId,
        lastFiredAt: Long,
    ): Result<Unit>
}
