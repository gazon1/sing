package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.RecurrenceRule
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

class RruleGeneratorTest {

    private fun generate(rule: RecurrenceRule): String? = RruleGenerator.generate(rule)

    companion object {
        @JvmStatic
        fun happyCases(): List<Arguments> = listOf(
            Arguments.of(RecurrenceRule.Daily, "FREQ=DAILY"),
            Arguments.of(RecurrenceRule.Weekly(listOf(1)), "FREQ=WEEKLY;BYDAY=MO"),
            Arguments.of(RecurrenceRule.Weekly(listOf(1, 3, 5)), "FREQ=WEEKLY;BYDAY=MO,WE,FR"),
            Arguments.of(RecurrenceRule.Monthly(15), "FREQ=MONTHLY;BYMONTHDAY=15"),
            Arguments.of(RecurrenceRule.Yearly, "FREQ=YEARLY"),
            Arguments.of(RecurrenceRule.Custom("FREQ=DAILY;INTERVAL=2"), "FREQ=DAILY;INTERVAL=2"),
        )

        // Pass raw List<Int> instead of RecurrenceRule.Weekly — constructing
        // RecurrenceRule.Weekly(invalidList) throws before the test runs.
        @JvmStatic
        fun throwsCases(): List<Arguments> = listOf(
            Arguments.of(listOf(8)),
            Arguments.of(emptyList<Int>()),
        )
    }

    @ParameterizedTest(name = "{0} → \"{1}\"")
    @MethodSource("happyCases")
    fun `generate produces expected rrule`(rule: RecurrenceRule, expected: String) {
        assertEquals(expected, generate(rule))
    }

    @ParameterizedTest(name = "weekdays={0} throws IllegalArgumentException")
    @MethodSource("throwsCases")
    fun `invalid weekly weekdays throw`(weekdays: List<Int>) {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            generate(RecurrenceRule.Weekly(weekdays))
        }
    }

    @org.junit.jupiter.api.Test
    fun `custom empty returns null`() {
        assertEquals(null, generate(RecurrenceRule.Custom("")))
    }
}
