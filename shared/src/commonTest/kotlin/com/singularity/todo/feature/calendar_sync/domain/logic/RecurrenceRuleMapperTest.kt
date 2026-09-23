package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.RecurrenceRule
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceRuleMapperTest {

    @Test
    fun daily_pattern_returns_daily() {
        assertEquals(RecurrenceRule.Daily, RecurrenceRuleMapper.map("DAILY"))
    }

    @Test
    fun weekly_pattern_with_days_returns_weekly() {
        assertEquals(
            RecurrenceRule.Weekly(listOf(1, 3, 5)),
            RecurrenceRuleMapper.map("WEEKLY:MON,WED,FRI"),
        )
    }

    @Test
    fun weekly_pattern_with_empty_days_returns_custom_preserving_input() {
        // Empty after colon — preserved as Custom so the original string is not silently dropped
        val result = RecurrenceRuleMapper.map("WEEKLY:")
        assertEquals(RecurrenceRule.Custom("WEEKLY:"), result)
    }

    @Test
    fun weekly_pattern_with_invalid_day_returns_custom() {
        // Unknown token after colon → Custom to avoid silently generating bad RRULE
        val result = RecurrenceRuleMapper.map("WEEKLY:INVALID")
        assertEquals(RecurrenceRule.Custom("WEEKLY:INVALID"), result)
    }

    @Test
    fun monthly_pattern_with_day_returns_monthly() {
        assertEquals(RecurrenceRule.Monthly(15), RecurrenceRuleMapper.map("MONTHLY:15"))
    }

    @Test
    fun monthly_pattern_out_of_range_throws() {
        // Day 32 is out of 1..31 range → Monthly init require throws IllegalArgumentException
        var thrown = false
        try {
            RecurrenceRuleMapper.map("MONTHLY:32")
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertEquals(true, thrown)
    }

    @Test
    fun yearly_pattern_returns_yearly() {
        assertEquals(RecurrenceRule.Yearly, RecurrenceRuleMapper.map("YEARLY"))
    }

    @Test
    fun empty_pattern_returns_custom_empty() {
        // Empty string — not null, so caller can decide what to do
        val result = RecurrenceRuleMapper.map("")
        assertEquals(RecurrenceRule.Custom(""), result)
    }
}
