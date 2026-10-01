package com.singularity.todo.feature.reminders.data

import com.singularity.todo.core.database.ProjectReminderDao
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.reminders.ProjectReminder
import com.singularity.todo.feature.reminders.ProjectReminderId
import com.singularity.todo.feature.reminders.domain.port.ProjectRemindersRepository
import com.singularity.todo.feature.reminders.toEntity
import com.singularity.todo.feature.reminders.toProjectReminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

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
