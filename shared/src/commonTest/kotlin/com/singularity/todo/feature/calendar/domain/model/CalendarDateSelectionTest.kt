package com.singularity.todo.feature.calendar.domain.model

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CalendarDateSelection] — the days picked for one task.
 *
 * Pure: no dispatcher, no repository, no Compose.
 */
@Tag("fast")
class CalendarDateSelectionTest {

    private val first = LocalDate(2026, 10, 5)
    private val second = LocalDate(2026, 10, 12)

    @Test
    fun `nothing is picked to begin with`() {
        val selection = CalendarDateSelection()

        assertFalse(selection.isActive)
        assertTrue(selection.dates.isEmpty())
    }

    @Test
    fun `entering the mode picks the day that was pressed`() {
        val selection = CalendarDateSelection().enter(first)

        assertTrue(selection.isActive)
        assertEquals(setOf(first), selection.dates)
    }

    @Test
    fun `a toggle picks a day that was not picked`() {
        val selection = CalendarDateSelection().enter(first).toggle(second)

        assertEquals(setOf(first, second), selection.dates)
    }

    @Test
    fun `a toggle unpicks a day that was picked`() {
        val selection = CalendarDateSelection().enter(first).toggle(first)

        assertTrue(selection.dates.isEmpty())
    }

    @Test
    fun `leaving the mode forgets the picked days`() {
        val selection = CalendarDateSelection().enter(first).toggle(second).exit()

        assertFalse(selection.isActive)
        assertTrue(selection.dates.isEmpty())
    }
}
