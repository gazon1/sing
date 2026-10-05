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
     * When this series stops, or `null` for an unbounded series — which is every
     * rule written before this field existed, and the default for a new one.
     *
     * A constructor property on each variant rather than a mutable field on this
     * base: `data class` `equals`, `hashCode`, `copy` and `toString` are generated
     * from constructor properties only. A `var` here would be invisible to all
     * four, so `copy()` would silently drop the end date and two rules differing
     * only by end date would compare equal — in a value that Room stores and the
     * sync diff compares.
     */
    abstract val termination: RecurrenceTermination?

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
    @Serializable
    data class Interval(
        override val base: RecurrenceBase,
        val amount: Int,
        val unit: DateTimeUnit.DateBased,
        override val termination: RecurrenceTermination? = null,
    ) : RecurrenceSpec()

    /**
     * Weekly recurrence on specific weekdays.
     *
     * @param base      The base mode.
     * @param weekdays ISO-8601 weekday numbers: 1=Monday … 7=Sunday.
     *                  At least one must be provided.
     */
    @Serializable
    data class Weekly(
        override val base: RecurrenceBase,
        val weekdays: Set<Int>,
        override val termination: RecurrenceTermination? = null,
    ) : RecurrenceSpec() {
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
    @Serializable
    data class Monthly(
        override val base: RecurrenceBase,
        val dayOfMonth: Int,
        override val termination: RecurrenceTermination? = null,
    ) : RecurrenceSpec() {
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
    @Serializable
    data class Yearly(
        override val base: RecurrenceBase,
        val month: Int,
        val day: Int,
        override val termination: RecurrenceTermination? = null,
    ) : RecurrenceSpec() {
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

/**
 * When a recurring series stops.
 *
 * Currently only an end date. "After N occurrences" is deliberately absent: it
 * needs a counter that increases monotonically across devices, and the sync layer
 * merges whole rows last-writer-wins with no per-field policy — so two devices
 * would disagree about the count. That needs protocol work before it can be a
 * field. See ADR `2026-10-05-recurrence-end-date-in-spec-blob`.
 *
 * @param endDate Last date on which the series may still produce an occurrence.
 *        The boundary is **inclusive** — "repeat until Dec 31" means Dec 31 is
 *        shown. `null` inside a non-null termination means unbounded, which the
 *        picker represents by omitting the termination entirely.
 */
@Serializable
data class RecurrenceTermination(val endDate: kotlinx.datetime.LocalDate? = null)

/**
 * True when [nextDue] falls past this series' end date, so it must not recur.
 *
 * The single place the boundary is read, so no branch of the completion use case
 * re-implements it. An occurrence landing exactly on [RecurrenceTermination.endDate]
 * is still allowed — only one past it stops the series.
 */
fun RecurrenceSpec.isExhaustedBy(nextDue: kotlinx.datetime.LocalDate): Boolean {
    val end = termination?.endDate ?: return false
    return nextDue > end
}
