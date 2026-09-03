package com.singularity.todo.feature.reminders

import com.singularity.todo.core.database.ReminderDao
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [ReminderRepository].
 * Delegates all persistence to [ReminderDao]; this class only maps entities → domain.
 */
class RoomReminderRepository(private val dao: ReminderDao) : ReminderRepository {

    override fun watchAll(userId: UserId): Flow<List<Reminder>> =
        dao.watchAll(userId.value).map { list -> list.map { it.toReminder() } }

    override fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>> =
        dao.watchByTask(taskId.value, userId.value).map { list -> list.map { it.toReminder() } }

    override fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>> =
        dao.watchDueBefore(nowEpochMs, userId.value).map { list -> list.map { it.toReminder() } }

    override suspend fun upsert(reminder: Reminder) {
        dao.upsert(reminder.toEntity(System.currentTimeMillis()))
    }

    override suspend fun delete(reminderId: ReminderId, userId: UserId) {
        dao.delete(reminderId.value, userId.value)
    }

    override suspend fun deleteByTask(taskId: TaskId, userId: UserId) {
        dao.deleteByTask(taskId.value, userId.value)
    }

    override suspend fun getById(reminderId: ReminderId, userId: UserId): Reminder? =
        dao.getById(reminderId.value, userId.value)?.toReminder()
}
