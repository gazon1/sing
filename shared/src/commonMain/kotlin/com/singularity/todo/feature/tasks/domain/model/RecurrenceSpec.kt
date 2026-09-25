package com.singularity.todo.feature.tasks.domain.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.serialization.Serializable

/**
 * Specifies how a task repeats after completion or when due.
 *
 * ## DSL reference (Orgzly + Tasks.org compatible)
 *
 * | Syntax | Type | Description |
 * |--------|------|-------------|
 * | `+1w` | [Interval] FROM_COMPLETION | Every week from completion |
 * | `++1w` | [Interval] FROM_DUE | Every week from due date |
 * | `!+1w` | [CatchUp] FROM_COMPLETION | Catch-up: fills gaps |
 * | `.+1w` | [Interval] FROM_COMPLETION | Same as `+1w` (explicit) |
 * | `.++1w` | [Interval] FROM_DUE | Same as `++1w` (explicit) |
 * | `every Mon,Wed,Fri` | [Weekly] FROM_DUE | Specific weekdays |
 * | `every 2 weeks` | [Interval] FROM_DUE | Every 2 weeks |
 * | `1st of month` | [Monthly] FROM_DUE | Monthly on day 1 |
 * | `every Jan 1` | [Yearly] FROM_DUE | Yearly on specific date |
 *
 * ## Base modes
 *
 * - [RecurrenceBase.FROM_DUE]: next occurrence is relative to the due date of the current occurrence.
 * - [RecurrenceBase.FROM_COMPLETION]: next occurrence is relative to when the current occurrence was completed.
 * - [RecurrenceBase.CATCH_UP]: like FROM_COMPLETION but creates copies for each missed occurrence
 *   up to [MAX_MISSED] (10). Used to fill in gaps when tasks are completed late.
 *
 * @see RecurrenceParser for DSL parsing.
 * @see RecurrenceCalculator for next-occurrence computation.
 */
@Serializable
sealed class RecurrenceSpec {

    /** The base used to compute the next occurrence. Subclasses must override. */
    abstract val base: RecurrenceBase

    /**
     * The base used to compute the next occurrence.
     */
    @Serializable
    enum class RecurrenceBase {
        /** Next occurrence is relative to the task's due date. */
        FROM_DUE,

        /** Next occurrence is relative to when the current occurrence was completed. */
        FROM_COMPLETION,

        /** Like FROM_COMPLETION but generates catch-up copies for missed occurrences. */
        CATCH_UP,
    }

    /**
     * A simple interval recurrence (every N days/weeks/months/years).
     *
     * @param base   The base mode (FROM_DUE, FROM_COMPLETION, or CATCH_UP).
     * @param amount The multiplier for [unit] (e.g. 2 means "every 2 [unit]").
     * @param unit   The time unit. Must be a date-based unit (DAY, WEEK, MONTH, YEAR).
     */
    data class Interval(override val base: RecurrenceBase, val amount: Int, val unit: DateTimeUnit.DateBased) :
        RecurrenceSpec()

    /**
     * Weekly recurrence on specific weekdays.
     *
     * @param base      The base mode.
     * @param weekdays ISO-8601 weekday numbers: 1=Monday … 7=Sunday.
     *                  At least one must be provided.
     */
    data class Weekly(override val base: RecurrenceBase, val weekdays: Set<Int>) : RecurrenceSpec() {
        init {
            require(weekdays.isNotEmpty()) { "weekdays must not be empty" }
            require(weekdays.all { it in 1..7 }) { "weekday must be in 1..7 (ISO-8601)" }
        }
    }

    /**
     * Monthly recurrence on a specific day of the month.
     *
     * @param base       The base mode.
     * @param dayOfMonth Day of month (1..31). If the month has fewer days,
     *                   the last day of that month is used.
     */
    data class Monthly(override val base: RecurrenceBase, val dayOfMonth: Int) : RecurrenceSpec() {
        init {
            require(dayOfMonth in 1..31) { "dayOfMonth must be in 1..31" }
        }
    }

    /**
     * Yearly recurrence on a specific calendar date.
     *
     * @param base  The base mode.
     * @param month Month of year (1..12).
     * @param day  Day of month (1..31).
     */
    data class Yearly(override val base: RecurrenceBase, val month: Int, val day: Int) : RecurrenceSpec() {
        init {
            require(month in 1..12) { "month must be in 1..12" }
            require(day in 1..31) { "day must be in 1..31" }
        }
    }

    companion object {
        /** Maximum number of catch-up copies created when completing a CATCH_UP task. */
        const val MAX_MISSED = 10
    }
}
