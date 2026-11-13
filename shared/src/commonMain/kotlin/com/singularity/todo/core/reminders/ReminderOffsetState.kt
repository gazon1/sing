package com.singularity.todo.core.reminders

/**
 * What the app knows about the reminder of a task.
 *
 * Three states, not two: a nullable [ReminderOffset] cannot tell "the task has
 * no reminder" from "the task has a reminder whose offset this build does not
 * know" — and the second one is a reminder. It is a reminder written by an
 * older build, by a newer one, or by another client, and the UI must show it
 * as a reminder: shown as nothing, it would be a fact the database holds and
 * the screen does not.
 *
 * The unknown offset is kept as the number of minutes it was stored with, so
 * the value is never lost and never silently rewritten as the nearest offset
 * the app happens to offer.
 */
sealed interface ReminderOffsetState {
    /** The task has no reminder. */
    data object None : ReminderOffsetState

    /** The task has a reminder with an offset this build knows. */
    data class Known(val offset: ReminderOffset) : ReminderOffsetState

    /** The task has a reminder with an offset this build does not know. */
    data class Unknown(val offsetMinutes: Int) : ReminderOffsetState

    companion object {
        /**
         * The state of a reminder stored with [offsetMinutes], or of no reminder
         * at all when there is none.
         */
        fun of(offsetMinutes: Int?): ReminderOffsetState =
            when {
                offsetMinutes == null -> {
                    None
                }

                else -> {
                    ReminderOffset
                        .fromMinutes(offsetMinutes)
                        ?.let { Known(it) }
                        ?: Unknown(offsetMinutes)
                }
            }
    }
}
