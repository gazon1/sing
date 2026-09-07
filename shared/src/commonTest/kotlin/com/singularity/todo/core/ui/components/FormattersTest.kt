package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class FormattersTest {

    // ─── formatDueChip ────────────────────────────────────────────────────────────

    @Test
    fun `formatDueChip returns null when date is null`() {
        val today = LocalDate(2026, 9, 7)
        val result = formatDueChip(date = null, time = "09:00", today = today)
        assertNull(result)
    }

    @Test
    fun `formatDueChip formats today with time`() {
        val today = LocalDate(2026, 9, 7)
        val result = formatDueChip(date = today, time = "09:00", today = today)!!
        assertEquals("Today, 09:00", result.text)
        assertEquals(DueVisualState.Today, result.state)
    }

    @Test
    fun `formatDueChip formats today without time`() {
        val today = LocalDate(2026, 9, 7)
        val result = formatDueChip(date = today, time = null, today = today)!!
        assertEquals("Today", result.text)
        assertEquals(DueVisualState.Today, result.state)
    }

    @Test
    fun `formatDueChip formats today with blank time`() {
        val today = LocalDate(2026, 9, 7)
        val result = formatDueChip(date = today, time = "  ", today = today)!!
        assertEquals("Today", result.text)
        assertEquals(DueVisualState.Today, result.state)
    }

    @Test
    fun `formatDueChip formats tomorrow`() {
        val today = LocalDate(2026, 9, 7)
        val tomorrow = LocalDate(2026, 9, 8)
        val result = formatDueChip(date = tomorrow, time = "14:00", today = today)!!
        assertEquals("Tomorrow, 14:00", result.text)
        assertEquals(DueVisualState.Future, result.state)
    }

    @Test
    fun `formatDueChip formats yesterday`() {
        val today = LocalDate(2026, 9, 7)
        val yesterday = LocalDate(2026, 9, 6)
        val result = formatDueChip(date = yesterday, time = null, today = today)!!
        assertEquals("Yesterday", result.text)
        assertEquals(DueVisualState.Overdue, result.state)
    }

    @Test
    fun `formatDueChip formats past date as overdue`() {
        val today = LocalDate(2026, 9, 7)
        val fiveDaysAgo = LocalDate(2026, 9, 2)
        val result = formatDueChip(date = fiveDaysAgo, time = "10:00", today = today)!!
        assertEquals("2026-09-02, 10:00", result.text)
        assertEquals(DueVisualState.Overdue, result.state)
    }

    @Test
    fun `formatDueChip formats far future as future`() {
        val today = LocalDate(2026, 9, 7)
        val nextWeek = LocalDate(2026, 9, 14)
        val result = formatDueChip(date = nextWeek, time = null, today = today)!!
        assertEquals("2026-09-14", result.text)
        assertEquals(DueVisualState.Future, result.state)
    }
}
