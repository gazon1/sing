package com.singularity.todo.feature.reminders

import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [ReminderRepository].
 * Delegates all persistence to [ReminderDao]; this class only maps entities → domain.
 */
class RoomReminderRepository(
    private val dao: ReminderDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : ReminderRepository {

    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────

    override fun watchAllForCurrentUser(): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.watchAll(uid.value).map { list -> list.map { it.toReminder() } }
        }

    override fun watchByTaskForCurrentUser(taskId: TaskId): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.watchByTask(taskId.value, uid.value).map { list -> list.map { it.toReminder() } }
        }

    override fun watchDueBeforeForCurrentUser(nowEpochMs: Long): Flow<List<Reminder>> =
        currentUser.observeForCurrentUser { uid ->
            dao.watchDueBefore(nowEpochMs, uid.value).map { list -> list.map { it.toReminder() } }
        }

    // ─── Explicit userId overloads ──────────────────────────────────────────

    override fun watchAll(userId: UserId): Flow<List<Reminder>> =
        dao.watchAll(userId.value).map { list -> list.map { it.toReminder() } }

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>> =
        dao.watchByTask(taskId.value, userId.value).map { list -> list.map { it.toReminder() } }

    override fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>> =
        dao.watchDueBefore(nowEpochMs, userId.value).map { list -> list.map { it.toReminder() } }

    override suspend fun upsert(reminder: Reminder): Result<Unit> = runCatching {
        dao.upsert(reminder.toEntity(clock.now().toEpochMilliseconds()))
    }

    override suspend fun delete(reminderId: ReminderId, userId: UserId): Result<Unit> = runCatching {
        dao.delete(reminderId.value, userId.value)
    }

    override suspend fun deleteByTask(taskId: TaskId, userId: UserId): Result<Unit> = runCatching {
        dao.deleteByTask(taskId.value, userId.value)
    }

    override suspend fun getById(reminderId: ReminderId, userId: UserId): Result<Reminder?> = runCatching {
        dao.getById(reminderId.value, userId.value)?.toReminder()
    }
}
