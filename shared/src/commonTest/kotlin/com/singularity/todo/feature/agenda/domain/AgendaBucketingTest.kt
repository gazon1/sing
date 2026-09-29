package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.DateRange
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the [RelativeBucket.toDateRange] bucketing function that maps relative date
 * descriptors (Today, ThisWeek, Overdue, …) to concrete date ranges.
 *
 * The agenda UI relies on these ranges to group tasks into sections.
 * A bug here causes tasks to appear in the wrong section or be invisible.
 *
 * @see RelativeBucket
 * @see DateRange
 */
class AgendaBucketingTest {

    // Wednesday 2026-09-16 — used so we can reason about week boundaries
    private val today = LocalDate(2026, Month.SEPTEMBER, 16)

    // ─── Today ────────────────────────────────────────────────────────────────

    @Test
    fun `Today bucket is today`() {
        val range = RelativeBucket.Today.toDateRange(today)
        assertEquals(DateRange(today, today), range)
    }

    // ─── Yesterday ───────────────────────────────────────────────────────────

    @Test
    fun `Yesterday bucket is one day before today`() {
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val range = RelativeBucket.Yesterday.toDateRange(today)
        assertEquals(DateRange(yesterday, yesterday), range)
    }

    // ─── Tomorrow ────────────────────────────────────────────────────────────

    @Test
    fun `Tomorrow bucket is one day after today`() {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val range = RelativeBucket.Tomorrow.toDateRange(today)
        assertEquals(DateRange(tomorrow, tomorrow), range)
    }

    // ─── ThisWeek ────────────────────────────────────────────────────────────

    @Test
    fun `ThisWeek bucket covers Monday to Sunday of the current week`() {
        // 2026-09-16 is Wednesday; Monday = 2026-09-14, Sunday = 2026-09-20
        val range = RelativeBucket.ThisWeek.toDateRange(today)
        assertEquals(
            DateRange(LocalDate(2026, 9, 14), LocalDate(2026, 9, 20)),
            range,
        )
    }

    // ─── NextWeek ────────────────────────────────────────────────────────────

    @Test
    fun `NextWeek bucket covers Monday to Sunday of the following week`() {
        // Monday 2026-09-14 + 7 = Monday 2026-09-21
        val range = RelativeBucket.NextWeek.toDateRange(today)
        assertEquals(
            DateRange(LocalDate(2026, 9, 21), LocalDate(2026, 9, 27)),
            range,
        )
    }

    // ─── ThisMonth ───────────────────────────────────────────────────────────

    @Test
    fun `ThisMonth bucket starts at 1st of month and ends at last day`() {
        val range = RelativeBucket.ThisMonth.toDateRange(today)
        assertEquals(DateRange(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)), range)
    }

    // ─── NextMonth ──────────────────────────────────────────────────────────

    @Test
    fun `NextMonth bucket starts at 1st of next month and ends at its last day`() {
        val range = RelativeBucket.NextMonth.toDateRange(today)
        assertEquals(DateRange(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)), range)
    }

    // ─── Overdue ────────────────────────────────────────────────────────────

    @Test
    fun `Overdue bucket covers 1970-01-01 through yesterday`() {
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val range = RelativeBucket.Overdue.toDateRange(today)
        assertEquals(DateRange(LocalDate(1970, 1, 1), yesterday), range)
    }

    // ─── NoDate ─────────────────────────────────────────────────────────────

    @Test
    fun `NoDate bucket is sentinel range used for tasks with null dueDate`() {
        // The sentinel range (1970-01-01, 1970-01-01) is recognised by AgendaEvaluator
        // as the "null date" range. Tasks with dueDate=null are bucketed here.
        val range = RelativeBucket.NoDate.toDateRange(today)
        assertEquals(DateRange(LocalDate(1970, 1, 1), LocalDate(1970, 1, 1)), range)
    }
}
