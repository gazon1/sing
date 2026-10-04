@file:Suppress("TooManyFunctions")

package com.singularity.todo.feature.reminders.data

import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.reminders.toEntity
import com.singularity.todo.feature.reminders.toReminder
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Room-backed implementation of [ReminderRepository].
 * Delegates all persistence to [ReminderDao]; this class only maps entities → domain.
 */
class ReminderRepositoryImpl(
    private val dao: ReminderDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : ReminderRepository {

    // ─── Generic CRUD (ambient user) ─────────────────────────────────────────

    override fun observeAll(): Flow<List<Reminder>> = currentUser.observeForCurrentUser { uid ->
        dao.watchAll(uid.value).map { list -> list.map { it.toReminder() } }
    }

    override fun observe(id: ReminderId): Flow<Reminder?> = currentUser.observeForCurrentUser { uid ->
        dao.watchByIdForUser(id.value, uid.value).map { it?.toReminder() }
    }

    override suspend fun get(id: ReminderId): Reminder? {
        val uid = currentUser.scopedUserId.value
        return dao.getById(id.value, uid.value)?.toReminder()
    }

    override suspend fun upsert(reminder: Reminder): Result<Unit> = runCatchingCancellable {
        val uid = currentUser.scopedUserId.value
        currentUser.assertCanWrite(entityId = reminder.id.value, entityUserId = reminder.userId)
        val toInsert = reminder.copy(userId = uid)
        dao.upsert(toInsert.toEntity(clock.now().toEpochMillis()))
    }

    override suspend fun delete(id: ReminderId): Result<Unit> = runCatchingCancellable {
        val uid = currentUser.scopedUserId.value
        dao.delete(id.value, uid.value)
    }

    override suspend fun delete(id: ReminderId, userId: com.singularity.todo.core.ids.UserId): Result<Unit> =
        runCatchingCancellable {
            dao.delete(id.value, userId.value)
        }

    // ─── Domain methods ───────────────────────────────────────────────────────

    override fun observeRecurringTaskIds(): Flow<Set<TaskId>> = currentUser.observeForCurrentUser { uid ->
        dao.watchRecurringTaskIds(uid.value).map { list ->
            list.mapTo(linkedSetOf()) { TaskId.fromString(it) }
        }
    }

    override fun watchByTask(taskId: TaskId): Flow<List<Reminder>> = currentUser.observeForCurrentUser { uid ->
        dao.watchByTask(taskId.value, uid.value).map { list -> list.map { it.toReminder() } }
    }

    override fun watchDueBefore(nowEpochMs: Long): Flow<List<Reminder>> = currentUser.observeForCurrentUser { uid ->
        dao.getDueBefore(nowEpochMs, uid.value).map { list -> list.map { it.toReminder() } }
    }

    override fun watchRecentDueBefore(nowEpochMs: Long, limit: Int): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.getRecentDueBefore(nowEpochMs, uid.value, limit).map { list -> list.map { it.toReminder() } }
        }

    override suspend fun deleteByTask(taskId: TaskId): Result<Unit> = runCatchingCancellable {
        val uid = currentUser.scopedUserId.value
        dao.deleteByTask(taskId.value, uid.value)
    }

    override suspend fun markFired(reminderId: ReminderId, lastFiredAt: Long): Result<Unit> = runCatchingCancellable {
        val uid = currentUser.scopedUserId.value
        dao.setLastFiredAt(reminderId.value, uid.value, lastFiredAt, clock.now().toEpochMillis())
    }
}
