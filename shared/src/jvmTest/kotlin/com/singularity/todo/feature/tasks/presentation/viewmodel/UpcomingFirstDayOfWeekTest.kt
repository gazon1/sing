package com.singularity.todo.feature.tasks.presentation.viewmodel

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [UpcomingFirstDayOfWeek].
 *
 * Pure function — no Compose, no Koin, no mocks.
 */
class UpcomingFirstDayOfWeekTest {

    @Test
    fun of_monday_returnsSameDate() {
        // Monday = ordinal 0
        val monday = LocalDate(2026, 9, 14)
        assertEquals(monday, UpcomingFirstDayOfWeek.of(monday))
    }

    @Test
    fun of_sunday_returnsMondayOfSameWeek() {
        // Sunday = ordinal 6, daysFromMonday = (6 + 6) % 7 = 5
        val sunday = LocalDate(2026, 9, 20)
        val expected = LocalDate(2026, 9, 14)
        assertEquals(expected, UpcomingFirstDayOfWeek.of(sunday))
    }

    @Test
    fun of_wednesday_returnsMondayOfSameWeek() {
        // Wednesday = ordinal 2, daysFromMonday = (2 + 6) % 7 = 1
        val wednesday = LocalDate(2026, 9, 16)
        val expected = LocalDate(2026, 9, 14)
        assertEquals(expected, UpcomingFirstDayOfWeek.of(wednesday))
    }

    @Test
    fun of_saturday_returnsMondayOfSameWeek() {
        // Saturday = ordinal 5, daysFromMonday = (5 + 6) % 7 = 4
        val saturday = LocalDate(2026, 9, 19)
        val expected = LocalDate(2026, 9, 14)
        assertEquals(expected, UpcomingFirstDayOfWeek.of(saturday))
    }

    @Test
    fun of_firstDayOfWeek_weekBoundary() {
        // Verify week boundaries: Monday and Sunday
        val weekStart = LocalDate(2026, 9, 21) // Monday
        val weekEnd = LocalDate(2026, 9, 27)   // Sunday

        assertEquals(weekStart, UpcomingFirstDayOfWeek.of(weekStart))
        assertEquals(weekStart, UpcomingFirstDayOfWeek.of(weekEnd))
    }
}
