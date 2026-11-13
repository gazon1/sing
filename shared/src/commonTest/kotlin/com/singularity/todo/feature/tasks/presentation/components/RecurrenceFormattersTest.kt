package com.singularity.todo.feature.tasks.presentation.components

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure parts of the recurrence formatter.
 *
 * The sentence itself is built from Compose resources, so what a person reads
 * is proved by the rendering tests (desktopApp) — this file proves the two
 * decisions the code makes before any word is read: which date the series
 * starts on, and which unit the interval counts in.
 *
 * The unusual units are written as `DateTimeUnit.DAY * 3` and not as
 * `DateTimeUnit.DayBased(3)`: the JVM initializes a nested class after the
 * class that holds it, and two threads doing that at the same time — one
 * touching `DateTimeUnit.DAY`, one touching `DayBased` — deadlock on the
 * class-initialization monitors. This module runs its tests concurrently, and
 * the constant form initializes the whole class on one thread before the
 * multiplication.
 */
@Tag("fast")
@Epic("Tasks")
@Feature("Presentation")
class RecurrenceFormattersTest {
    private fun d(
        y: Int,
        m: Int,
        day: Int,
    ) = LocalDate(y, m, day)

    // ─── The start date ────────────────────────────────────────────────────────

    @Test
    fun `a rule measured from the due date starts on the due date`() {
        assertEquals(d(2026, 11, 12), recurrenceStartDate(RecurrenceBase.FROM_DUE, d(2026, 11, 12)))
    }

    @Test
    fun `a rule measured from the due date has no start without one`() {
        assertNull(recurrenceStartDate(RecurrenceBase.FROM_DUE, null))
    }

    @Test
    fun `a catch-up rule is measured from the due date`() {
        assertEquals(d(2026, 11, 12), recurrenceStartDate(RecurrenceBase.CATCH_UP, d(2026, 11, 12)))
    }

    @Test
    fun `a catch-up rule has no start without a due date`() {
        assertNull(recurrenceStartDate(RecurrenceBase.CATCH_UP, null))
    }

    @Test
    fun `a rule measured from completion has no start date`() {
        // The series is measured from every completion, so its anchor moves with
        // every occurrence: there is no date the series starts on, and the due
        // date is not one of them.
        assertNull(recurrenceStartDate(RecurrenceBase.FROM_COMPLETION, d(2026, 11, 12)))
        assertNull(recurrenceStartDate(RecurrenceBase.FROM_COMPLETION, null))
    }

    // ─── The unit ──────────────────────────────────────────────────────────────

    @Test
    fun `a day interval counts days`() {
        assertEquals(
            RecurrenceInterval(1, RecurrenceUnit.DAY),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.DAY)),
        )
    }

    @Test
    fun `a week interval counts weeks`() {
        assertEquals(
            RecurrenceInterval(1, RecurrenceUnit.WEEK),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)),
        )
    }

    @Test
    fun `a month interval counts months`() {
        assertEquals(
            RecurrenceInterval(1, RecurrenceUnit.MONTH),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH)),
        )
    }

    @Test
    fun `a year interval counts years`() {
        assertEquals(
            RecurrenceInterval(1, RecurrenceUnit.YEAR),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.YEAR)),
        )
    }

    @Test
    fun `the amount multiplies the unit`() {
        assertEquals(
            RecurrenceInterval(3, RecurrenceUnit.DAY),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)),
        )
        assertEquals(
            RecurrenceInterval(2, RecurrenceUnit.WEEK),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 2, DateTimeUnit.WEEK)),
        )
    }

    @Test
    fun `a unit that is not a whole week stays in days`() {
        assertEquals(
            RecurrenceInterval(3, RecurrenceUnit.DAY),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.DAY * 3)),
        )
    }

    @Test
    fun `a whole number of weeks is read as weeks`() {
        assertEquals(
            RecurrenceInterval(2, RecurrenceUnit.WEEK),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.DAY * 14)),
        )
    }

    @Test
    fun `a unit that is not a whole year stays in months`() {
        assertEquals(
            RecurrenceInterval(3, RecurrenceUnit.MONTH),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH * 3)),
        )
    }

    @Test
    fun `a whole number of years is read as years`() {
        assertEquals(
            RecurrenceInterval(2, RecurrenceUnit.YEAR),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH * 24)),
        )
    }

    @Test
    fun `a century is a hundred years, not a hundred months`() {
        assertEquals(
            RecurrenceInterval(100, RecurrenceUnit.YEAR),
            intervalOf(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.CENTURY)),
        )
    }

    // ─── baseLabel ──────────────────────────────────────────────────────────────

    @Test
    fun `baseLabel returns human-readable strings`() {
        assertEquals("From due date", RecurrenceFormatters.baseLabel(RecurrenceBase.FROM_DUE))
        assertEquals("From completion", RecurrenceFormatters.baseLabel(RecurrenceBase.FROM_COMPLETION))
        assertEquals("Catch-up (fill gaps)", RecurrenceFormatters.baseLabel(RecurrenceBase.CATCH_UP))
    }

    // ─── nextOccurrencePreview ─────────────────────────────────────────────────

    @Test
    fun `nextOccurrencePreview shows next date`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.WEEK)
        val preview = RecurrenceFormatters.nextOccurrencePreview(d(2026, 1, 15), spec)
        assertEquals("Next: 2026-01-22", preview)
    }

    @Test
    fun `nextOccurrencePreview monthly shows first of next month`() {
        val spec = Monthly(RecurrenceBase.FROM_DUE, 1)
        val preview = RecurrenceFormatters.nextOccurrencePreview(d(2026, 1, 1), spec)
        assertEquals("Next: 2026-02-01", preview)
    }

    // ─── The rules the picker offers ───────────────────────────────────────────

    @Test
    fun `the rules the picker offers keep their shape`() {
        // The end date belongs to the rule, not to the base: the same rule with
        // and without a bound is the same rule with and without a sentence tail.
        val unbounded = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
        val bounded = unbounded.copy(termination = RecurrenceTermination(d(2027, 1, 29)))
        assertNull(unbounded.termination?.endDate)
        assertEquals(d(2027, 1, 29), bounded.termination?.endDate)
        assertEquals(RecurrenceBase.FROM_DUE, bounded.base)
    }

    @Test
    fun `a weekly rule keeps its weekdays`() {
        val weekly = Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5))
        assertEquals(setOf(1, 3, 5), weekly.weekdays)
    }

    @Test
    fun `a yearly rule keeps its date`() {
        val yearly = Yearly(RecurrenceBase.FROM_DUE, 12, 25)
        assertEquals(12, yearly.month)
        assertEquals(25, yearly.day)
    }
}
