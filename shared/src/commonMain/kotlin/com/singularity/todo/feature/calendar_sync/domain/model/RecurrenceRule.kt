package com.singularity.todo.feature.calendar_sync.domain.model

/**
 * Structured recurrence representation.
 *
 * Covers the common cases that map cleanly to RRULE.
 * [Custom] is the escape hatch for any cron-like pattern that doesn't fit the
 * sealed variants — stored as-is and passed through to the system calendar.
 *
 * Weekdays are encoded as ISO numbers: Mon=1 … Sun=7.
 */
sealed interface RecurrenceRule {

    /** Fires every day. */
    data object Daily : RecurrenceRule

    /**
     * Fires on specific weekdays.
     * @param weekdays Sorted list of ISO weekday numbers (1=Mon … 7=Sun), non-empty.
     */
    data class Weekly(val weekdays: List<Int>) : RecurrenceRule {
        init {
            require(weekdays.isNotEmpty()) { "weekdays must be non-empty" }
            require(weekdays.all { it in 1..7 }) { "weekday must be 1..7" }
        }
    }

    /**
     * Fires on a specific day of the month (1‑31).
     * @param day Day of month. 29‑31 may not exist in all months — the system
     *            calendar handles the rollback to the last valid day.
     */
    data class Monthly(val day: Int) : RecurrenceRule {
        init {
            require(day in 1..31) { "day must be 1..31" }
        }
    }

    /** Fires on the same day each year. */
    data object Yearly : RecurrenceRule

    /**
     * Catch-all for any pattern not expressible via the sealed variants.
     * Stored and passed through verbatim — the system calendar provider accepts
     * arbitrary RRULE strings via the EventsContract.
     */
    data class Custom(val rrule: String) : RecurrenceRule
}
