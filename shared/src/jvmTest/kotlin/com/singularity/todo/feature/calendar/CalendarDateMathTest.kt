package com.singularity.todo.feature.calendar

import com.singularity.todo.feature.calendar.domain.logic.goNext
import com.singularity.todo.feature.calendar.domain.logic.goPrevious
import com.singularity.todo.feature.calendar.domain.logic.headerLabel
import com.singularity.todo.feature.calendar.domain.logic.monthGridDates
import com.singularity.todo.feature.calendar.domain.logic.visibleRange
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.logic.firstDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.lastDayOfMonth
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [com.singularity.todo.feature.calendar.domain.logic] date arithmetic.
 */
class CalendarDateMathTest {

    // ─── monthGridDates ───────────────────────────────────────────────────────

    @Test
    fun `monthGridDates returns 42 dates for 6 weeks`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        val grid = monthGridDates(sept16)
        assertEquals(42, grid.size)
    }

    @Test
    fun `monthGridDates starts on Monday before the 1st`() {
        // September 2026: 1st is a Tuesday → grid starts on Monday Aug 31
        val sept1 = LocalDate(2026, Month.SEPTEMBER, 1)
        val grid = monthGridDates(sept1)
        assertEquals(LocalDate(2026, Month.AUGUST, 31), grid.first())
    }

    @Test
    fun `monthGridDates starts on the 1st when it is a Monday`() {
        // June 2026: 1st is a Monday
        val june1 = LocalDate(2026, Month.JUNE, 1)
        val grid = monthGridDates(june1)
        assertEquals(LocalDate(2026, Month.JUNE, 1), grid.first())
    }

    @Test
    fun `monthGridDates ends on Sunday after the last day of month`() {
        // September 2026: 1st is Tuesday, 30th is Wednesday.
        // Grid starts Monday before (Aug 31) + 42 days = Oct 11 (Sunday).
        val sept1 = LocalDate(2026, Month.SEPTEMBER, 1)
        val grid = monthGridDates(sept1)
        assertEquals(LocalDate(2026, Month.OCTOBER, 11), grid.last())
    }

    @Test
    fun `monthGridDates includes all days of the target month`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        val grid = monthGridDates(sept16)
        // Every day from Sept 1 to Sept 30 must be present
        val septDays = (1..30).map { LocalDate(2026, Month.SEPTEMBER, it) }
        septDays.forEach { day ->
            assertEquals(true, day in grid, "Expected $day to be in grid")
        }
    }

    // ─── visibleRange ─────────────────────────────────────────────────────────

    @Test
    fun `visibleRange DAY returns single anchor date`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        val range = visibleRange(sept16, CalendarViewMode.DAY)
        assertEquals(listOf(sept16), range)
    }

    @Test
    fun `visibleRange FOUR_DAYS returns 4 consecutive days starting at anchor`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        val range = visibleRange(sept16, CalendarViewMode.FOUR_DAYS)
        assertEquals(4, range.size)
        assertEquals(sept16, range[0])
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 17), range[1])
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 18), range[2])
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 19), range[3])
    }

    @Test
    fun `visibleRange WEEK returns Monday to Sunday of anchor's week`() {
        // September 16, 2026 is a Wednesday
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        val range = visibleRange(sept16, CalendarViewMode.WEEK)
        assertEquals(7, range.size)
        // Week starts Monday Sept 14
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 14), range.first())
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 20), range.last())
    }

    @Test
    fun `visibleRange MONTH returns 6-week grid`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        val range = visibleRange(sept16, CalendarViewMode.MONTH)
        assertEquals(42, range.size)
    }

    // ─── goNext / goPrevious ───────────────────────────────────────────────────

    @Test
    fun `goNext DAY advances by one day`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 17), goNext(sept16, CalendarViewMode.DAY))
    }

    @Test
    fun `goPrevious DAY rewinds by one day`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 15), goPrevious(sept16, CalendarViewMode.DAY))
    }

    @Test
    fun `goNext FOUR_DAYS advances by 4 days`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 20), goNext(sept16, CalendarViewMode.FOUR_DAYS))
    }

    @Test
    fun `goNext WEEK advances by 7 days`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 23), goNext(sept16, CalendarViewMode.WEEK))
    }

    @Test
    fun `goNext MONTH advances to first of next month`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.OCTOBER, 1), goNext(sept16, CalendarViewMode.MONTH))
    }

    @Test
    fun `goNext MONTH at December goes to January next year`() {
        val dec15 = LocalDate(2026, Month.DECEMBER, 15)
        assertEquals(LocalDate(2027, Month.JANUARY, 1), goNext(dec15, CalendarViewMode.MONTH))
    }

    @Test
    fun `goPrevious MONTH goes to first of previous month`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.AUGUST, 1), goPrevious(sept16, CalendarViewMode.MONTH))
    }

    @Test
    fun `goPrevious MONTH at January goes to December previous year`() {
        val jan10 = LocalDate(2027, Month.JANUARY, 10)
        assertEquals(LocalDate(2026, Month.DECEMBER, 1), goPrevious(jan10, CalendarViewMode.MONTH))
    }

    // ─── headerLabel ─────────────────────────────────────────────────────────

    @Test
    fun `headerLabel MONTH shows month and year`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals("September 2026", headerLabel(sept16, CalendarViewMode.MONTH))
    }

    @Test
    fun `headerLabel DAY shows full date`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals("September 16, 2026", headerLabel(sept16, CalendarViewMode.DAY))
    }

    @Test
    fun `headerLabel FOUR_DAYS shows range in same month`() {
        // Sept 16-19, all same month
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals("September 16 – 19, 2026", headerLabel(sept16, CalendarViewMode.FOUR_DAYS))
    }

    @Test
    fun `headerLabel FOUR_DAYS shows range across months`() {
        // Sept 30 to Oct 3
        val sept30 = LocalDate(2026, Month.SEPTEMBER, 30)
        assertEquals("September 30 – October 3, 2026", headerLabel(sept30, CalendarViewMode.FOUR_DAYS))
    }

    @Test
    fun `headerLabel WEEK shows range in same month`() {
        // Sept 14-20, all same month
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals("September 14 – 20, 2026", headerLabel(sept16, CalendarViewMode.WEEK))
    }

    @Test
    fun `headerLabel WEEK shows range across months`() {
        // Dec 28, 2026 (Monday) to Jan 3, 2027 (Sunday)
        // Year shown only once at end (same year on both dates)
        val dec28 = LocalDate(2026, Month.DECEMBER, 28)
        assertEquals("December 28 – January 3, 2027", headerLabel(dec28, CalendarViewMode.WEEK))
    }

    // ─── firstDayOfMonth / lastDayOfMonth ────────────────────────────────────

    @Test
    fun `firstDayOfMonth returns the 1st of the same month`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 1), firstDayOfMonth(sept16))
    }

    @Test
    fun `lastDayOfMonth September returns 30`() {
        val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 30), lastDayOfMonth(sept16))
    }

    @Test
    fun `lastDayOfMonth February in leap year returns 29`() {
        val feb15Leap = LocalDate(2024, Month.FEBRUARY, 15)
        assertEquals(LocalDate(2024, Month.FEBRUARY, 29), lastDayOfMonth(feb15Leap))
    }

    @Test
    fun `lastDayOfMonth February in non-leap year returns 28`() {
        val feb15 = LocalDate(2025, Month.FEBRUARY, 15)
        assertEquals(LocalDate(2025, Month.FEBRUARY, 28), lastDayOfMonth(feb15))
    }

    @Test
    fun `lastDayOfMonth December returns 31`() {
        val dec25 = LocalDate(2026, Month.DECEMBER, 25)
        assertEquals(LocalDate(2026, Month.DECEMBER, 31), lastDayOfMonth(dec25))
    }

    @Test
    fun `lastDayOfMonth at month boundary gives correct next month first`() {
        // Verify: lastDayOfMonth(Jan) + 1 day = Feb 1
        val jan31 = LocalDate(2026, Month.JANUARY, 31)
        val lastJan = lastDayOfMonth(jan31)
        assertEquals(LocalDate(2026, Month.JANUARY, 31), lastJan)
    }
}
