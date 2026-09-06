package com.singularity.todo.feature.reminders

import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow

/**
 * Repository port for [Reminder] persistence.
 * Platform implementations: Room (Android/desktop) via [ReminderDao].
 */
interface ReminderRepository {
    fun watchAll(userId: UserId): Flow<List<Reminder>>
    fun watchByTask(taskId: TaskId, userId: UserId): Flow<List<Reminder>>
    fun watchDueBefore(nowEpochMs: Long, userId: UserId): Flow<List<Reminder>>
    suspend fun upsert(reminder: Reminder): Result<Unit>
    suspend fun delete(reminderId: ReminderId, userId: UserId): Result<Unit>
    suspend fun deleteByTask(taskId: TaskId, userId: UserId): Result<Unit>
    suspend fun getById(reminderId: ReminderId, userId: UserId): Result<Reminder?>
}
