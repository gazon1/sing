package com.singularity.todo.feature.reminders

/**
 * Pure business logic for firing a single [Reminder].
 *
 * This object is stateless — all inputs are passed as parameters.
 * Testing requires no Android infrastructure, Koin, or coroutines.
 *
 * ## Stale-text fix
 * The notification title/body are computed at fire time (not at schedule time)
 * by reading the fresh task title from Room DB inside [AlarmReceiver][com.singularity.todo.feature.alarms.AlarmReceiver].
 * This avoids showing stale task titles in notifications when the user renames a task
 * after creating the reminder.
 */
internal object ReminderFireLogic {

    /**
     * Result of firing a reminder.
     *
     * @param title Notification title shown to the user.
     * @param body Notification body text.
     * @param shouldDelete When `true`, the reminder should be deleted from the database
     *                     after a successful fire (one-shot reminders).
     *                     When `false`, the reminder is kept (recurring reminders are
     *                     re-scheduled by callers after each fire).
     */
    data class Outcome(val title: String, val body: String, val shouldDelete: Boolean)

    /**
     * Computes the notification content and post-fire disposition for [reminder].
     *
     * @param reminder The reminder that is firing.
     * @param taskTitle Fresh task title from Room DB (may be `null` if task was deleted).
     */
    fun execute(reminder: Reminder, taskTitle: String?): Outcome = Outcome(
        title = taskTitle?.let { "Reminder: $it" } ?: "Task Reminder",
        body = "A reminder is due",
        shouldDelete = reminder.recurringPattern == null,
    )
}
