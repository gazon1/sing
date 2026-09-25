package com.singularity.todo.feature.calendar

import com.singularity.todo.feature.calendar.domain.logic.YearMonth
import com.singularity.todo.feature.calendar.domain.logic.firstDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.goNext
import com.singularity.todo.feature.calendar.domain.logic.goPrevious
import com.singularity.todo.feature.calendar.domain.logic.headerLabel
import com.singularity.todo.feature.calendar.domain.logic.lastDayOfMonth
import com.singularity.todo.feature.calendar.domain.logic.monthGridDates
import com.singularity.todo.feature.calendar.domain.logic.monthPageRange
import com.singularity.todo.feature.calendar.domain.logic.pageForYearMonth
import com.singularity.todo.feature.calendar.domain.logic.toLocalDate
import com.singularity.todo.feature.calendar.domain.logic.toYearMonth
import com.singularity.todo.feature.calendar.domain.logic.visibleRange
import com.singularity.todo.feature.calendar.domain.logic.yearMonthForPage
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [com.singularity.todo.feature.calendar.domain.logic] date arithmetic.
 */
class CalendarDateMathTest {

    private val sept16 = LocalDate(2026, Month.SEPTEMBER, 16)

    // ─── monthGridDates ───────────────────────────────────────────────────────

    @Test
    fun `monthGridDates returns 42 dates for 6 weeks`() {
        val grid = monthGridDates(sept16)
        assertEquals(42, grid.size)
    }

    @Test
    fun `monthGridDates starts on Monday before the 1st`() {
        // September 2026: 1st is a Tuesday → grid starts on Monday Aug 31
        val grid = monthGridDates(LocalDate(2026, Month.SEPTEMBER, 1))
        assertEquals(LocalDate(2026, Month.AUGUST, 31), grid.first())
    }

    @Test
    fun `monthGridDates starts on the 1st when it is a Monday`() {
        // June 2026: 1st is a Monday
        val grid = monthGridDates(LocalDate(2026, Month.JUNE, 1))
        assertEquals(LocalDate(2026, Month.JUNE, 1), grid.first())
    }

    @Test
    fun `monthGridDates ends on Sunday after the last day of month`() {
        // September 2026: 1st is Tuesday, 30th is Wednesday.
        // Grid starts Monday before (Aug 31) + 42 days = Oct 11 (Sunday).
        val grid = monthGridDates(LocalDate(2026, Month.SEPTEMBER, 1))
        assertEquals(LocalDate(2026, Month.OCTOBER, 11), grid.last())
    }

    @Test
    fun `monthGridDates includes all days of the target month`() {
        val grid = monthGridDates(sept16)
        // Every day from Sept 1 to Sept 30 must be present
        val septDays = (1..30).map { LocalDate(2026, Month.SEPTEMBER, it) }
        septDays.forEach { day ->
            assertEquals(true, day in grid, "Expected $day to be in grid")
        }
    }

    // ─── visibleRange ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(CalendarViewMode::class)
    fun `visibleRange returns correct size for each mode`(mode: CalendarViewMode) {
        val range = visibleRange(sept16, mode)
        val expectedSize = when (mode) {
            CalendarViewMode.DAY -> 1
            CalendarViewMode.FOUR_DAYS -> 4
            CalendarViewMode.WEEK -> 7
            CalendarViewMode.MONTH -> 42
        }
        assertEquals(expectedSize, range.size)
    }

    @Test
    fun `visibleRange DAY returns anchor as sole element`() {
        assertEquals(listOf(sept16), visibleRange(sept16, CalendarViewMode.DAY))
    }

    @Test
    fun `visibleRange FOUR_DAYS starts at anchor`() {
        val range = visibleRange(sept16, CalendarViewMode.FOUR_DAYS)
        assertEquals(sept16, range.first())
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 19), range.last())
    }

    @Test
    fun `visibleRange WEEK starts Monday before anchor`() {
        // September 16, 2026 is a Wednesday; week starts Monday Sept 14
        val range = visibleRange(sept16, CalendarViewMode.WEEK)
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 14), range.first())
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 20), range.last())
    }

    // ─── goNext / goPrevious ───────────────────────────────────────────────────

    @ParameterizedTest(name = "goNext {0} → {1}")
    @MethodSource("goNextCases")
    fun `goNext advances by correct interval`(mode: CalendarViewMode, expected: LocalDate) {
        assertEquals(expected, goNext(sept16, mode))
    }

    @ParameterizedTest(name = "goPrevious {0} → {1}")
    @MethodSource("goPreviousCases")
    fun `goPrevious rewinds by correct interval`(mode: CalendarViewMode, expected: LocalDate) {
        assertEquals(expected, goPrevious(sept16, mode))
    }

    @Test
    fun `goNext MONTH at December goes to January next year`() {
        val dec15 = LocalDate(2026, Month.DECEMBER, 15)
        assertEquals(LocalDate(2027, Month.JANUARY, 1), goNext(dec15, CalendarViewMode.MONTH))
    }

    @Test
    fun `goPrevious MONTH at January goes to December previous year`() {
        val jan10 = LocalDate(2027, Month.JANUARY, 10)
        assertEquals(LocalDate(2026, Month.DECEMBER, 1), goPrevious(jan10, CalendarViewMode.MONTH))
    }

    // ─── headerLabel ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1} → \"{2}\"")
    @MethodSource("headerLabelSameMonthCases")
    fun `headerLabel shows correct text for mode in same month`(
        date: LocalDate,
        mode: CalendarViewMode,
        expected: String,
    ) {
        assertEquals(expected, headerLabel(date, mode))
    }

    @ParameterizedTest(name = "{0} {1} → \"{2}\" (cross-month)")
    @MethodSource("headerLabelCrossMonthCases")
    fun `headerLabel shows correct text across month boundary`(
        date: LocalDate,
        mode: CalendarViewMode,
        expected: String,
    ) {
        assertEquals(expected, headerLabel(date, mode))
    }

    // ─── firstDayOfMonth / lastDayOfMonth ────────────────────────────────────

    @Test
    fun `firstDayOfMonth returns the 1st of the same month`() {
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 1), firstDayOfMonth(sept16))
    }

    @ParameterizedTest(name = "{0} → day {1}")
    @MethodSource("lastDayOfMonthCases")
    fun `lastDayOfMonth returns correct last day`(date: LocalDate, expectedDay: Int) {
        assertEquals(expectedDay, lastDayOfMonth(date).day)
    }

    // ─── YearMonth conversion ────────────────────────────────────────────────

    @Test
    fun `LocalDate toYearMonth preserves year and month`() {
        assertEquals(YearMonth(2026, Month.SEPTEMBER), sept16.toYearMonth())
    }

    @Test
    fun `YearMonth toLocalDate returns first of month`() {
        assertEquals(LocalDate(2026, Month.SEPTEMBER, 1), YearMonth(2026, Month.SEPTEMBER).toLocalDate())
    }

    // ─── monthPageRange ──────────────────────────────────────────────────────

    @Test
    fun `monthPageRange with default span gives 240 months centred on anchor`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        val (min, max) = monthPageRange(anchor)
        assertEquals(YearMonth(2016, Month.SEPTEMBER), min)
        assertEquals(YearMonth(2036, Month.SEPTEMBER), max)
    }

    @Test
    fun `monthPageRange handles December anchor correctly`() {
        val anchor = YearMonth(2026, Month.DECEMBER)
        val (min, max) = monthPageRange(anchor)
        assertEquals(YearMonth(2016, Month.DECEMBER), min)
        assertEquals(YearMonth(2036, Month.DECEMBER), max)
    }

    // ─── yearMonthForPage / pageForYearMonth ─────────────────────────────────

    @Test
    fun `yearMonthForPage middle page equals anchor`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        assertEquals(anchor, yearMonthForPage(anchor, page = 120))
    }

    @Test
    fun `yearMonthForPage page+1 is next month`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        assertEquals(YearMonth(2026, Month.OCTOBER), yearMonthForPage(anchor, page = 121))
    }

    @Test
    fun `yearMonthForPage page-1 is previous month`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        assertEquals(YearMonth(2026, Month.AUGUST), yearMonthForPage(anchor, page = 119))
    }

    @Test
    fun `yearMonthForPage crosses year boundary forward`() {
        val anchor = YearMonth(2026, Month.DECEMBER)
        assertEquals(YearMonth(2027, Month.JANUARY), yearMonthForPage(anchor, page = 121))
    }

    @Test
    fun `yearMonthForPage crosses year boundary backward`() {
        val anchor = YearMonth(2026, Month.JANUARY)
        assertEquals(YearMonth(2025, Month.DECEMBER), yearMonthForPage(anchor, page = 119))
    }

    @Test
    fun `pageForYearMonth returns span for anchor itself`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        assertEquals(120, pageForYearMonth(anchor, anchor))
    }

    @Test
    fun `pageForYearMonth returns null for out-of-range target`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        assertNull(pageForYearMonth(anchor, YearMonth(2026, Month.OCTOBER), span = 0))
    }

    @Test
    fun `pageForYearMonth and yearMonthForPage are inverse`() {
        val anchor = YearMonth(2026, Month.SEPTEMBER)
        val targets = listOf(
            YearMonth(2020, Month.JANUARY),
            YearMonth(2025, Month.JULY),
            YearMonth(2026, Month.SEPTEMBER),
            YearMonth(2027, Month.MARCH),
            YearMonth(2030, Month.DECEMBER),
        )
        targets.forEach { target ->
            val page = pageForYearMonth(anchor, target)
                ?: error("target $target out of range")
            assertEquals(target, yearMonthForPage(anchor, page))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // companion object — all @MethodSource providers in one place
    // ═══════════════════════════════════════════════════════════════════════════
    companion object {
        @JvmStatic
        fun goNextCases(): List<Arguments> = listOf(
            Arguments.of(CalendarViewMode.DAY, LocalDate(2026, Month.SEPTEMBER, 17)),
            Arguments.of(CalendarViewMode.FOUR_DAYS, LocalDate(2026, Month.SEPTEMBER, 20)),
            Arguments.of(CalendarViewMode.WEEK, LocalDate(2026, Month.SEPTEMBER, 23)),
            Arguments.of(CalendarViewMode.MONTH, LocalDate(2026, Month.OCTOBER, 1)),
        )

        @JvmStatic
        fun goPreviousCases(): List<Arguments> = listOf(
            Arguments.of(CalendarViewMode.DAY, LocalDate(2026, Month.SEPTEMBER, 15)),
            Arguments.of(CalendarViewMode.FOUR_DAYS, LocalDate(2026, Month.SEPTEMBER, 12)),
            Arguments.of(CalendarViewMode.WEEK, LocalDate(2026, Month.SEPTEMBER, 9)),
            Arguments.of(CalendarViewMode.MONTH, LocalDate(2026, Month.AUGUST, 1)),
        )

        @JvmStatic
        fun headerLabelSameMonthCases(): List<Arguments> = listOf(
            Arguments.of(LocalDate(2026, Month.SEPTEMBER, 16), CalendarViewMode.DAY, "September 16, 2026"),
            Arguments.of(LocalDate(2026, Month.SEPTEMBER, 16), CalendarViewMode.FOUR_DAYS, "September 16 – 19, 2026"),
            Arguments.of(LocalDate(2026, Month.SEPTEMBER, 16), CalendarViewMode.WEEK, "September 14 – 20, 2026"),
            Arguments.of(LocalDate(2026, Month.SEPTEMBER, 16), CalendarViewMode.MONTH, "September 2026"),
        )

        @JvmStatic
        fun headerLabelCrossMonthCases(): List<Arguments> = listOf(
            Arguments.of(LocalDate(2026, Month.SEPTEMBER, 30), CalendarViewMode.FOUR_DAYS, "September 30 – October 3, 2026"),
            Arguments.of(LocalDate(2026, Month.DECEMBER, 28), CalendarViewMode.WEEK, "December 28 – January 3, 2027"),
        )

        @JvmStatic
        fun lastDayOfMonthCases(): List<Arguments> = listOf(
            Arguments.of(LocalDate(2026, Month.SEPTEMBER, 16), 30),
            Arguments.of(LocalDate(2024, Month.FEBRUARY, 15), 29), // leap year
            Arguments.of(LocalDate(2025, Month.FEBRUARY, 15), 28), // non-leap year
            Arguments.of(LocalDate(2026, Month.DECEMBER, 25), 31),
        )
    }
}
