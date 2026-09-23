package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.RecurrenceRule
import kotlin.test.Test
import kotlin.test.assertEquals

class RruleGeneratorTest {

    private fun generate(rule: RecurrenceRule): String? = RruleGenerator.generate(rule)

    @Test
    fun daily_generates_freq_daily() {
        assertEquals("FREQ=DAILY", generate(RecurrenceRule.Daily))
    }

    @Test
    fun weekly_single_day_generates_freq_weekly_byday() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", generate(RecurrenceRule.Weekly(listOf(1))))
    }

    @Test
    fun weekly_multi_day_generates_freq_weekly_byday_multi() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            generate(RecurrenceRule.Weekly(listOf(1, 3, 5))),
        )
    }

    @Test
    fun weekly_invalid_day_throws() {
        // Weekday 8 is outside 1..7 range → Weekly init require throws IllegalArgumentException
        var thrown = false
        try {
            generate(RecurrenceRule.Weekly(listOf(8)))
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertEquals(true, thrown)
    }

    @Test
    fun weekly_empty_list_throws() {
        // Empty weekday list is rejected by Weekly init require
        var thrown = false
        try {
            generate(RecurrenceRule.Weekly(emptyList()))
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertEquals(true, thrown)
    }

    @Test
    fun monthly_generates_freq_monthly_bymonthday() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", generate(RecurrenceRule.Monthly(15)))
    }

    @Test
    fun yearly_generates_freq_yearly() {
        assertEquals("FREQ=YEARLY", generate(RecurrenceRule.Yearly))
    }

    @Test
    fun custom_valid_passes_through() {
        val rrule = "FREQ=DAILY;INTERVAL=2"
        assertEquals(rrule, generate(RecurrenceRule.Custom(rrule)))
    }

    @Test
    fun custom_empty_returns_null() {
        assertEquals(null, generate(RecurrenceRule.Custom("")))
    }
}
