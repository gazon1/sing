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
 *
 * ## Platform support
 * See [isSupported]. A platform whose implementation is a no-op must report `false`,
 * so that no caller persists a reminder that will never fire.
 */
interface ReminderScheduler {

    /**
     * Whether this platform can actually arm an alarm for a [Reminder].
     *
     * ## Why this is a member of the port and not a UI-layer constant
     *
     * A reminder is a two-part claim: the row in the database *and* the alarm in the
     * OS. Both have to happen for the reminder to mean anything, and a caller that
     * writes the row on a platform that cannot arm the alarm has produced a reminder
     * that never fires while the UI says it was set. `JvmReminderScheduler` is a no-op
     * on all three methods, so on desktop every reminder was accepted and silently
     * discarded at fire time.
     *
     * Reading the flag here means the *implementation* is the authority — the same
     * way `CalendarSyncUiState.isSupported` takes its value from the provider rather
     * than from a hardcoded `platform == "Android"` check in common code. A capability
     * that has to be duplicated per call site is one that will drift.
     *
     * Callers must consult this before persisting a reminder, not only before
     * scheduling: the row is the part that lies to the user, because the alarm is
     * invisible and a stored row renders as a working reminder forever.
     */
    val isSupported: Boolean

    /**
     * Schedules a one-shot or recurring [reminder] to fire at [Reminder.fireAt].
     * Recurring reminders are rescheduled by callers after each fire.
     *
     * Callers are expected to have checked [isSupported] first; on an unsupported
     * platform this must not pretend to have armed anything.
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
