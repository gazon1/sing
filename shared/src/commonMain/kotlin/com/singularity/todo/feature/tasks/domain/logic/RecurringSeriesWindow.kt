package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.isExhaustedBy
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * The future occurrences a recurring series holds.
 *
 * Pure: no clock, no repository, no I/O. The caller supplies the rule and the
 * anchor, so the window is a value that can be asserted on — the same rule at the
 * same anchor gives the same dates, today and next year.
 *
 * ## The shape of the window
 *
 * ```
 * anchor   = the current occurrence's date
 * horizon  = anchor + 14 days
 * within   = the rule's dates in (anchor, horizon], nearest first, capped at 10
 * beyond   = the rule's first date after the horizon, if the cap leaves room
 * window   = within + beyond
 * ```
 *
 * The anchor is **not** in the window: it is the occurrence that is already there.
 *
 * ## Why the window is anchored at the occurrence and not at today
 *
 * A horizon measured from *today* makes the set of rows a function of the calendar
 * day, so something has to notice the day change and write — a daily job that may
 * not have run before the user opens the app, or a read that mutates the database.
 * Anchoring at the occurrence removes the day from the rule and the scheduler with
 * it: the window changes when an occurrence is consumed or the rule changes, and
 * nothing else.
 *
 * ## Why `beyond` exists
 *
 * A rule with a rare period — monthly, yearly — has no date inside a two-week
 * horizon. Without `beyond` such a series would have no next occurrence at all.
 *
 * ## The rule's end condition
 *
 * [RecurrenceCalculator.nextOccurrence] does not read `termination` — it dispatches
 * only on the rule's variant — so the window checks the boundary itself, through
 * [RecurrenceSpec.isExhaustedBy], the single place the boundary is read. A date past
 * the rule's end date is not produced, and the window stops there.
 *
 * @see RecurrenceCalculator for the date arithmetic.
 * @see com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
 *   for the caller that supplies the anchor per base.
 */
object RecurringSeriesWindow {

    /** How far ahead of the anchor the window looks, in days. */
    const val HORIZON_DAYS: Int = 14

    /** The most future occurrences a series holds at once. */
    const val MAX_FUTURE: Int = 10

    /**
     * The future occurrences of [rule] from [anchor].
     *
     * @param rule the recurrence rule.
     * @param anchor the date the rule is applied from — the current occurrence's
     *   date. Which date that is per base is the caller's business: the due date
     *   for `FROM_DUE` and `CATCH_UP`, the completion date for `FROM_COMPLETION`.
     * @param horizonDays how far ahead of [anchor] the window looks.
     * @param maxFuture the cap on how many occurrences the window holds.
     * @return the occurrence dates, nearest first, strictly after [anchor]. Empty
     *   when the rule has already run out.
     */
    fun plan(
        rule: RecurrenceSpec,
        anchor: LocalDate,
        horizonDays: Int = HORIZON_DAYS,
        maxFuture: Int = MAX_FUTURE,
    ): List<LocalDate> {
        if (maxFuture <= 0) return emptyList()
        val horizon = anchor.plus(horizonDays, DateTimeUnit.DAY)

        val within = mutableListOf<LocalDate>()
        var cursor = anchor
        while (within.size < maxFuture) {
            val next = RecurrenceCalculator.nextOccurrence(rule, cursor)
            // The rule has run out: nothing at or past this date may be produced.
            if (rule.isExhaustedBy(next)) return within
            if (next > horizon) {
                // The first date past the horizon is materialised while the cap
                // leaves room — a rare rule must still have a next occurrence.
                within += next
                return within
            }
            within += next
            cursor = next
        }
        return within
    }
}
