package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.RecurrenceRule
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

@Tag("fast")
class RecurrenceRuleMapperTest {

    companion object {
        @JvmStatic
        fun happyCases(): List<Arguments> = listOf(
            Arguments.of("DAILY", RecurrenceRule.Daily),
            Arguments.of("WEEKLY:MON,WED,FRI", RecurrenceRule.Weekly(listOf(1, 3, 5))),
            Arguments.of("WEEKLY:", RecurrenceRule.Custom("WEEKLY:")),
            Arguments.of("WEEKLY:INVALID", RecurrenceRule.Custom("WEEKLY:INVALID")),
            Arguments.of("MONTHLY:15", RecurrenceRule.Monthly(15)),
            Arguments.of("YEARLY", RecurrenceRule.Yearly),
            Arguments.of("", RecurrenceRule.Custom("")),
        )

        @JvmStatic
        fun monthlyOutOfRangeCases(): List<Arguments> = listOf(
            Arguments.of("MONTHLY:32"),
            Arguments.of("MONTHLY:0"),
        )
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @MethodSource("happyCases")
    fun `map parses pattern to expected rule`(pattern: String, expected: RecurrenceRule) {
        assertEquals(expected, RecurrenceRuleMapper.map(pattern))
    }

    @ParameterizedTest(name = "\"{0}\" throws IllegalArgumentException")
    @MethodSource("monthlyOutOfRangeCases")
    fun `monthly out-of-range throws`(pattern: String) {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            RecurrenceRuleMapper.map(pattern)
        }
    }
}
