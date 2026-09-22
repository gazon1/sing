package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Platform-aware scheduler port for [Reminder] alarms.
 *
 * Android implementation: [AlarmManagerReminderScheduler][com.singularity.todo.feature.reminders.AlarmManagerReminderScheduler]
 * JVM implementation: [JvmReminderScheduler][com.singularity.todo.feature.reminders.JvmReminderScheduler]
 *
 * ## Multi-profile keys
 * All alarm keys are scoped to `userId` to prevent cross-profile collisions:
 * `"reminder:${userId.value}:${reminderId.value}"`
 */
interface ReminderScheduler {

    /**
     * Schedules a one-shot or recurring [reminder] to fire at [Reminder.fireAt].
     * Recurring reminders are rescheduled by callers after each fire.
     */
    suspend fun schedule(reminder: Reminder)

    /**
     * Cancels a scheduled alarm for [id] belonging to [userId].
     * No-op if no alarm is currently scheduled.
     */
    suspend fun cancel(id: ReminderId, userId: UserId)

    /**
     * Cancels all scheduled alarms for every reminder attached to [taskId].
     * Used when a task is deleted or has its due date removed.
     */
    suspend fun cancelByTask(taskId: TaskId, userId: UserId)
}
