package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * JVM stub implementation of [ReminderScheduler].
 *
 * Alarms require OS-level scheduling (AlarmManager on Android) which is not available on JVM.
 * This is a no-op implementation — reminders are never scheduled on desktop.
 */
class JvmReminderScheduler : ReminderScheduler {

    override suspend fun schedule(reminder: Reminder) {
        // No-op on JVM: alarms require AlarmManager
    }

    override suspend fun cancel(id: ReminderId, userId: UserId) {
        // No-op on JVM
    }

    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
        // No-op on JVM
    }
}
