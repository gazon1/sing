package com.singularity.todo.core.reminders

/**
 * When to fire a reminder relative to the task due date/time.
 */
enum class ReminderOffset(val minutes: Int, val label: String) {
    AT_DUE(0, "At due time"),
    FIFTEEN_MIN(15, "15 minutes before"),
    ONE_HOUR(60, "1 hour before"),
    ONE_DAY(1440, "1 day before"),
}
