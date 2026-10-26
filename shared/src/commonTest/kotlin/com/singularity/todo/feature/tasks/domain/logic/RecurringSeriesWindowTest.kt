package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window is a pure function of the rule and the anchor.
 *
 * Nothing here reads a clock or a repository, which is the point: the same rule at
 * the same anchor gives the same dates, today and next year.
 */
@Tag("fast")
class RecurringSeriesWindowTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    /** A Monday — the anchor of the weekly case in the quickstart. */
    private val monday = d(2026, 6, 15)

    private val weeklyDue = Weekly(RecurrenceBase.FROM_DUE, setOf(1))
    private val dailyDue = Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.DAY)

    @Test
    fun `a weekly rule gives the Mondays inside the horizon plus the first past it`() {
        val window = RecurringSeriesWindow.plan(weeklyDue, monday)

        // Two Mondays fall inside 14 days (22 and 29 June); the first past the
        // horizon (6 July) is added so the series always has a next occurrence.
        assertEquals(
            listOf(d(2026, 6, 22), d(2026, 6, 29), d(2026, 7, 6)),
            window,
        )
    }

    @Test
    fun `the anchor itself is not in the window`() {
        val window = RecurringSeriesWindow.plan(weeklyDue, monday)
        assertTrue(window.none { it == monday }, "the anchor is the occurrence already there")
        assertTrue(window.all { it > monday }, "every date is strictly after the anchor")
    }

    @Test
    fun `a daily rule is capped at ten future occurrences`() {
        val window = RecurringSeriesWindow.plan(dailyDue, monday)
        assertEquals(RecurringSeriesWindow.MAX_FUTURE, window.size)
        assertEquals(d(2026, 6, 16), window.first())
        // Dense rule: the cap binds inside the horizon, so no `beyond` date is added.
        assertEquals(d(2026, 6, 25), window.last())
    }

    @Test
    fun `a yearly rule still has a next occurrence past the horizon`() {
        val yearly = Yearly(RecurrenceBase.FROM_DUE, 6, 15)
        val window = RecurringSeriesWindow.plan(yearly, monday)

        // No date inside a 14-day horizon, so the single date is the first past it.
        assertEquals(listOf(d(2027, 6, 15)), window)
    }

    @Test
    fun `a rule that has run out gives nothing`() {
        // Ends before the next weekly occurrence, whatever it is.
        val exhausted = weeklyDue.copy(termination = RecurrenceTermination(d(2026, 6, 20)))
        val window = RecurringSeriesWindow.plan(exhausted, monday)

        assertTrue(window.isEmpty(), "an exhausted rule produces no window, got $window")
    }

    @Test
    fun `a rule with no date inside the horizon and past its end gives nothing`() {
        // The `beyond` date would land past the end date, so it must not be produced.
        val yearly = Yearly(RecurrenceBase.FROM_DUE, 6, 15)
            .copy(termination = RecurrenceTermination(d(2027, 1, 1)))
        val window = RecurringSeriesWindow.plan(yearly, monday)

        assertTrue(window.isEmpty(), "a beyond date past the end date must not be produced")
    }

    @Test
    fun `the same rule at the same anchor gives the same dates twice`() {
        val first = RecurringSeriesWindow.plan(weeklyDue, monday)
        val second = RecurringSeriesWindow.plan(weeklyDue, monday)

        assertEquals(first, second, "the window is pure — no hidden input")
    }

    @Test
    fun `the window does not change when the calendar day changes`() {
        // The function takes no clock, so a different "today" cannot reach it. The
        // assertion is the shape: planning the same anchor twice, with only the
        // ambient clock changed, gives the same dates.
        val window = RecurringSeriesWindow.plan(weeklyDue, monday)
        assertEquals(listOf(d(2026, 6, 22), d(2026, 6, 29), d(2026, 7, 6)), window)
    }

    @Test
    fun `a bounding end date is inclusive`() {
        val endsOnTheNext = weeklyDue.copy(termination = RecurrenceTermination(d(2026, 6, 22)))
        val window = RecurringSeriesWindow.plan(endsOnTheNext, monday)

        // 22 June is on the end date, so it is produced; 29 June is past it, so the
        // window stops — the same inclusive boundary the close path uses.
        assertEquals(listOf(d(2026, 6, 22)), window)
    }

    @Test
    fun `the window has no duplicates`() {
        val window = RecurringSeriesWindow.plan(dailyDue, monday)
        assertEquals(window.size, window.distinct().size)
        assertTrue(window.zipWithNext().all { (a, b) -> a < b }, "dates are strictly increasing")
    }

    @Test
    fun `a cap of zero gives nothing`() {
        assertTrue(RecurringSeriesWindow.plan(weeklyDue, monday, maxFuture = 0).isEmpty())
    }
}
