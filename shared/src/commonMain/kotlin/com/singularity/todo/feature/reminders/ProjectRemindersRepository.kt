package com.singularity.todo.feature.reminders

import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Repository port for [ProjectReminder] persistence.
 *
 * Deliberately narrower than [ReminderRepository]: the model has no type, no offset
 * and no recurrence, so there is nothing to query on them. Every method here is scoped
 * to the current user, and every write asserts ownership before it lands.
 */
interface ProjectRemindersRepository {

    /** All project reminders for the current user, soonest first. */
    fun observeAll(): Flow<List<ProjectReminder>>

    /** Project reminders attached to [projectId], soonest first. */
    fun watchByProject(projectId: ProjectId): Flow<List<ProjectReminder>>

    /** Single reminder observation by [id] for the current user. */
    fun observe(id: ProjectReminderId): Flow<ProjectReminder?>

    /** Get a reminder by [id] for the current user. */
    suspend fun get(id: ProjectReminderId): ProjectReminder?

    /**
     * Creates or updates a reminder.
     *
     * Rejects when the caller is not the entity's owner, rather than silently
     * re-stamping the owner — see [ProfileAwareCurrentUser.assertCanWrite].
     */
    suspend fun upsert(reminder: ProjectReminder): Result<Unit>

    /** Deletes a reminder by [id] for the current user. */
    suspend fun delete(id: ProjectReminderId): Result<Unit>

    /** Deletes a reminder by [id] for an explicit [userId]. Used by the fire receiver. */
    suspend fun delete(id: ProjectReminderId, userId: UserId): Result<Unit>

    /** Deletes every reminder attached to [projectId]. */
    suspend fun deleteByProject(projectId: ProjectId): Result<Unit>

    /** Project reminders due at or before [nowEpochMs], for the current user. */
    fun watchDueBefore(nowEpochMs: Long): Flow<List<ProjectReminder>>

    /**
     * Most-recent [limit] reminders due at or before [nowEpochMs].
     *
     * Backs the boot catch-up path, which must not load every past-due row.
     */
    fun watchRecentDueBefore(nowEpochMs: Long, limit: Int): Flow<List<ProjectReminder>>

    /** Records a successful fire, so a reboot does not re-fire the same reminder. */
    suspend fun markFired(reminderId: ProjectReminderId, lastFiredAt: Long): Result<Unit>
}

/**
 * Room-backed [ProjectRemindersRepository]. Maps entities to domain; owns no queries
 * of its own beyond what the DAO already scopes by `user_id`.
 */
class ProjectRemindersRepositoryImpl(
    private val dao: ProjectReminderDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : ProjectRemindersRepository {

    override fun observeAll(): Flow<List<ProjectReminder>> = currentUser.observeForCurrentUser { uid ->
        dao.watchAll(uid.value).map { list -> list.map { it.toProjectReminder() } }
    }

    override fun watchByProject(projectId: ProjectId): Flow<List<ProjectReminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.watchByProject(projectId.value, uid.value).map { list -> list.map { it.toProjectReminder() } }
        }

    override fun observe(id: ProjectReminderId): Flow<ProjectReminder?> = currentUser.observeForCurrentUser { uid ->
        dao.watchByIdForUser(id.value, uid.value).map { it?.toProjectReminder() }
    }

    override suspend fun get(id: ProjectReminderId): ProjectReminder? {
        val uid = currentUser.scopedUserId.value
        return dao.getById(id.value, uid.value)?.toProjectReminder()
    }

    override suspend fun upsert(reminder: ProjectReminder): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        currentUser.assertCanWrite(entityId = reminder.id.value, entityUserId = reminder.userId)
        dao.upsert(reminder.copy(userId = uid).toEntity(clock.now().toEpochMillis()))
    }

    override suspend fun delete(id: ProjectReminderId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        dao.delete(id.value, uid.value)
    }

    override suspend fun delete(id: ProjectReminderId, userId: UserId): Result<Unit> = runCatching {
        dao.delete(id.value, userId.value)
    }

    override suspend fun deleteByProject(projectId: ProjectId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        dao.deleteByProject(projectId.value, uid.value)
    }

    override fun watchDueBefore(nowEpochMs: Long): Flow<List<ProjectReminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.getDueBefore(nowEpochMs, uid.value).map { list -> list.map { it.toProjectReminder() } }
        }

    override fun watchRecentDueBefore(nowEpochMs: Long, limit: Int): Flow<List<ProjectReminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.getRecentDueBefore(nowEpochMs, uid.value, limit).map { list -> list.map { it.toProjectReminder() } }
        }

    override suspend fun markFired(reminderId: ProjectReminderId, lastFiredAt: Long): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        dao.setLastFiredAt(reminderId.value, uid.value, lastFiredAt, clock.now().toEpochMillis())
    }
}
