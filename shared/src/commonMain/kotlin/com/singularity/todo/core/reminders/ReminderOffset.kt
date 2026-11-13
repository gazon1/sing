package com.singularity.todo.core.reminders

/**
 * When to fire a reminder relative to the task due date/time.
 */
enum class ReminderOffset(val minutes: Int, val label: String) {
    AT_DUE(0, "At due time"),
    FIFTEEN_MIN(15, "15 minutes before"),
    ONE_HOUR(60, "1 hour before"),
    ONE_DAY(1440, "1 day before"),
    ;

    companion object {
        /**
         * The offset that is [minutes] minutes before the due time.
         *
         * The stored offset counts backwards (`Reminder.offsetMinutes` is negative),
         * so the lookup takes the absolute value: a reminder stored with
         * `offsetMinutes = -15` is the fifteen-minutes-before offset.
         *
         * `null` when the stored minutes are not one of the offsets — a reminder
         * written by an older build, or by another client, must not be presented as
         * one of the choices the sheet offers.
         */
        fun fromMinutes(minutes: Int): ReminderOffset? {
            // `abs(Int.MIN_VALUE)` is `Int.MIN_VALUE` — the one value whose
            // magnitude does not fit in an `Int`, so it stays negative and would
            // compare equal to no offset at all. It is mapped to the largest
            // magnitude an `Int` can hold instead, which matches no offset
            // either, and says so for the same reason: an offset this app does
            // not know is not one of its offsets.
            val magnitude = if (minutes == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(minutes)
            return entries.firstOrNull { it.minutes == magnitude }
        }
    }
}
