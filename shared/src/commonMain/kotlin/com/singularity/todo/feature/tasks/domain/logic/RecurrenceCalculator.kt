package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Pure next-occurrence calculator for [RecurrenceSpec].
 *
 * All methods are pure functions — no side effects, no `Clock.now()`, no I/O.
 * Thread-safe (no mutable state).
 *
 * @see RecurrenceParser for DSL parsing.
 */
object RecurrenceCalculator {

    /**
     * Computes the next occurrence date given the current occurrence's anchor date.
     *
     * - For [RecurrenceBase.FROM_DUE]: anchor is the task's `dueDate`.
     * - For [RecurrenceBase.FROM_COMPLETION]: anchor is `completedAt.toLocalDate()`.
     *
     * @param spec   The recurrence specification.
     * @param anchor The date to compute the next occurrence relative to.
     * @return The next occurrence date.
     */
    fun nextOccurrence(spec: RecurrenceSpec, anchor: LocalDate): LocalDate {
        return when (spec) {
            is RecurrenceSpec.Interval -> nextInterval(anchor, spec.amount, spec.unit)
            is RecurrenceSpec.Weekly   -> nextWeekly(anchor, spec.weekdays)
            is RecurrenceSpec.Monthly  -> nextMonthly(anchor, spec.dayOfMonth)
            is RecurrenceSpec.Yearly   -> nextYearly(anchor, spec.month, spec.day)
        }
    }

    /**
     * Returns how many occurrences were missed between [anchor] and [today] (inclusive),
     * capped at [RecurrenceSpec.MAX_MISSED].
     *
     * Used for CATCH_UP mode to determine how many copies to generate.
     *
     * @param spec   The recurrence specification.
     * @param anchor The anchor date of the last occurrence.
     * @param today  The current date (completion date).
     * @return Number of missed occurrences, in `0..MAX_MISSED`.
     */
    fun missedCount(spec: RecurrenceSpec, anchor: LocalDate, today: LocalDate): Int {
        if (today <= anchor) return 0
        val count = when (spec) {
            is RecurrenceSpec.Interval -> countIntervalMissed(anchor, today, spec.amount, spec.unit)
            is RecurrenceSpec.Weekly   -> countWeeklyMissed(anchor, today, spec.weekdays)
            is RecurrenceSpec.Monthly  -> countMonthlyMissed(anchor, today, spec.dayOfMonth)
            is RecurrenceSpec.Yearly   -> countYearlyMissed(anchor, today, spec.month, spec.day)
        }
        return count.coerceIn(0, RecurrenceSpec.MAX_MISSED)
    }

    // ─── Interval ────────────────────────────────────────────────────────────────

    private fun nextInterval(anchor: LocalDate, amount: Int, unit: DateTimeUnit.DateBased): LocalDate =
        anchor.plus(amount, unit)

    private fun countIntervalMissed(anchor: LocalDate, today: LocalDate, amount: Int, unit: DateTimeUnit.DateBased): Int {
        if (today <= anchor) return 0
        var count = 0
        var current = anchor
        while (true) {
            val next = current.plus(amount, unit)
            if (next > today) break
            count++
            current = next
        }
        return count
    }

    // ─── Weekly ─────────────────────────────────────────────────────────────────

    private fun nextWeekly(anchor: LocalDate, weekdays: Set<Int>): LocalDate {
        // anchor.dayOfWeek is DayOfWeek enum (MONDAY=0.ordinal=0, ..., SUNDAY=6.ordinal=6)
        // weekdays are ISO-8601 (Monday=1, ..., Sunday=7)
        val anchorIso = anchor.dayOfWeek.ordinal + 1 // 1=Monday … 7=Sunday
        val sorted = weekdays.sorted()
        val next = sorted.firstOrNull { it > anchorIso }
        return if (next != null) {
            anchor.plus(next - anchorIso, DateTimeUnit.DAY)
        } else {
            // Wrap to first weekday next week
            anchor.plus(sorted.first() + 7 - anchorIso, DateTimeUnit.DAY)
        }
    }

    private fun countWeeklyMissed(anchor: LocalDate, today: LocalDate, weekdays: Set<Int>): Int {
        if (today <= anchor) return 0
        var count = 0
        var current = anchor
        while (true) {
            val next = nextWeekly(current, weekdays)
            if (next > today) break
            count++
            current = next
        }
        return count
    }

    // ─── Monthly ────────────────────────────────────────────────────────────────

    private fun nextMonthly(anchor: LocalDate, dayOfMonth: Int): LocalDate {
        val nextMonth = anchor.plus(1, DateTimeUnit.MONTH)
        val lastDay = lastDayOfMonth(nextMonth.year, nextMonth.monthNumber)
        return LocalDate(nextMonth.year, nextMonth.monthNumber, minOf(dayOfMonth, lastDay))
    }

    private fun countMonthlyMissed(anchor: LocalDate, today: LocalDate, dayOfMonth: Int): Int {
        if (today <= anchor) return 0
        var count = 0
        var current = anchor
        while (true) {
            val next = nextMonthly(current, dayOfMonth)
            if (next > today) break
            count++
            current = next
        }
        return count
    }

    // ─── Yearly ─────────────────────────────────────────────────────────────────

    private fun nextYearly(anchor: LocalDate, month: Int, day: Int): LocalDate {
        val nextYear = anchor.year + 1
        val lastDay = lastDayOfMonth(nextYear, month)
        return LocalDate(nextYear, month, minOf(day, lastDay))
    }

    private fun countYearlyMissed(anchor: LocalDate, today: LocalDate, month: Int, day: Int): Int {
        if (today <= anchor) return 0
        var count = 0
        var current = anchor
        while (true) {
            val next = nextYearly(current, month, day)
            if (next > today) break
            count++
            current = next
        }
        return count
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private fun lastDayOfMonth(year: Int, month: Int): Int {
        val nextMonthOrdinal = if (month == 12) 0 else month
        val nextMonthYear = if (month == 12) year + 1 else year
        val firstOfNextMonth = LocalDate(nextMonthYear, Month.entries[nextMonthOrdinal], 1)
        return firstOfNextMonth.minus(1, DateTimeUnit.DAY).day
    }
}
