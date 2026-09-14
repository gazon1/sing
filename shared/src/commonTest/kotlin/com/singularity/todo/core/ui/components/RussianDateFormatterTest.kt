package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class RussianDateFormatterTest {

    @Test
    fun formatRussianDueDateReturnsNullWhenDateIsNull() {
        assertNull(formatRussianDueDate(null))
    }

    @Test
    fun formatRussianDueDateFormatsSaturday() {
        // 2026-09-05 is a Saturday
        val date = LocalDate(2026, 9, 5)
        assertEquals("Сб, 05 сент 2026", formatRussianDueDate(date))
    }

    @Test
    fun formatRussianDueDateFormatsMonday() {
        // 2026-09-07 is a Monday
        val date = LocalDate(2026, 9, 7)
        assertEquals("Пн, 07 сент 2026", formatRussianDueDate(date))
    }

    @Test
    fun formatRussianDueDateFormatsFirstDayOfMonth() {
        val date = LocalDate(2026, 1, 1)
        assertEquals("Чт, 01 янв 2026", formatRussianDueDate(date))
    }

    @Test
    fun formatRussianDueDateFormatsLastDayOfMonth() {
        // January 2026 has 31 days; 2026 is not a leap year
        val date = LocalDate(2026, 1, 31)
        assertEquals("Сб, 31 янв 2026", formatRussianDueDate(date))
    }

    @Test
    fun formatRussianDueDateFormatsMayWithGenitive() {
        // May uses genitive "мая"
        val date = LocalDate(2026, 5, 15)
        assertEquals("Пт, 15 мая 2026", formatRussianDueDate(date))
    }

    @Test
    fun formatRussianDueDateFormatsDecember() {
        val date = LocalDate(2026, 12, 25)
        assertEquals("Пт, 25 дек 2026", formatRussianDueDate(date))
    }

    @Test
    fun formatRussianDueDateFormatsAllDaysOfWeek() {
        // Monday 2026-09-07
        assertEquals("Пн, 07 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 7)))
        // Tuesday 2026-09-08
        assertEquals("Вт, 08 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 8)))
        // Wednesday 2026-09-09
        assertEquals("Ср, 09 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 9)))
        // Thursday 2026-09-10
        assertEquals("Чт, 10 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 10)))
        // Friday 2026-09-11
        assertEquals("Пт, 11 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 11)))
        // Saturday 2026-09-12
        assertEquals("Сб, 12 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 12)))
        // Sunday 2026-09-13
        assertEquals("Вс, 13 сент 2026", formatRussianDueDate(LocalDate(2026, 9, 13)))
    }
}
