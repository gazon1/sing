package com.singularity.todo.core.ui.textfields

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

/**
 * The filtering rules the text fields apply to what the user types.
 *
 * The rules are plain string functions because the `TextFieldBuffer` they run inside is not
 * constructible from a test. What is tested here is the decision, not the delivery.
 */
@Tag("fast")
class TextFieldTransformationsTest {

    companion object {
        @JvmStatic
        fun filterDigitsCases(): List<Arguments> = listOf(
            Arguments.of("1a2b3c", Int.MAX_VALUE, "123"),
            Arguments.of("abc-!@#", Int.MAX_VALUE, ""),
            Arguments.of("2026-10-19", Int.MAX_VALUE, "20261019"),
            Arguments.of("123456", 3, "123"),
            // Five characters but three digits, so the third digit survives the limit of three.
            Arguments.of("1a2b3", 3, "123"),
            Arguments.of("123456789", Int.MAX_VALUE, "123456789"),
        )

        @JvmStatic
        fun withinLengthCases(): List<Arguments> = listOf(
            Arguments.of("abc", 3, true),
            Arguments.of("abcd", 3, false),
            Arguments.of("", 3, true),
        )

        @JvmStatic
        fun recurrenceIntervalCases(): List<Arguments> = listOf(
            Arguments.of("1", true),
            Arguments.of("14", true),
            Arguments.of("0", false),
            Arguments.of("-3", false),
            Arguments.of("every week", false),
            Arguments.of("", false),
        )

        @JvmStatic
        fun dayOfMonthCases(): List<Arguments> = listOf(
            Arguments.of("1", true),
            Arguments.of("31", true),
            Arguments.of("0", false),
            Arguments.of("32", false),
            Arguments.of("monday", false),
        )

        // The digit filter runs first and the range check runs on the truncated text, so a
        // stray sign or letter costs the user nothing while "007"/"0031" truncate to "00".
        @JvmStatic
        fun filterRecurrenceIntervalCases(): List<Arguments> = listOf(
            Arguments.of("-3", "3"),
            Arguments.of("100", "10"),
            Arguments.of("0", null),
            Arguments.of("", null),
            Arguments.of("007", null),
        )

        @JvmStatic
        fun filterDayOfMonthCases(): List<Arguments> = listOf(
            Arguments.of("1a", "1"),
            Arguments.of("199", "19"),
            Arguments.of("32", null),
            Arguments.of("0031", null),
        )
    }

    @ParameterizedTest
    @MethodSource("filterDigitsCases")
    fun filterDigitsKeepsDigits(input: String, maxDigits: Int, expected: String) {
        assertEquals(expected, filterDigits(input, maxDigits = maxDigits))
    }

    @ParameterizedTest
    @MethodSource("withinLengthCases")
    fun isWithinLengthDecides(input: String, maxLength: Int, expected: Boolean) {
        assertEquals(expected, isWithinLength(input, maxLength = maxLength))
    }

    @ParameterizedTest
    @MethodSource("recurrenceIntervalCases")
    fun recurrenceIntervalIsValid(input: String, expected: Boolean) {
        assertEquals(expected, isValidRecurrenceInterval(input))
    }

    @ParameterizedTest
    @MethodSource("dayOfMonthCases")
    fun dayOfMonthIsValid(input: String, expected: Boolean) {
        assertEquals(expected, isValidDayOfMonth(input))
    }

    @ParameterizedTest
    @MethodSource("filterRecurrenceIntervalCases")
    fun filterRecurrenceIntervalKeeps(input: String, expected: String?) {
        assertEquals(expected, filterRecurrenceInterval(input))
    }

    @ParameterizedTest
    @MethodSource("filterDayOfMonthCases")
    fun filterDayOfMonthKeeps(input: String, expected: String?) {
        assertEquals(expected, filterDayOfMonth(input))
    }
}
