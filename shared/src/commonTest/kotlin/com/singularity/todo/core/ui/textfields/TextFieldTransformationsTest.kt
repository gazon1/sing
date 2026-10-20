package com.singularity.todo.core.ui.textfields

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filtering rules the text fields apply to what the user types.
 *
 * The rules are plain string functions because the `TextFieldBuffer` they run inside is not
 * constructible from a test. What is tested here is the decision, not the delivery.
 */
@Tag("fast")
class TextFieldTransformationsTest {

    @Test
    fun filterDigitsKeepsOnlyDigits() {
        assertEquals("123", filterDigits("1a2b3c"))
    }

    @Test
    fun filterDigitsDropsEveryNonDigit() {
        assertEquals("", filterDigits("abc-!@#"))
    }

    @Test
    fun filterDigitsKeepsTheOrderOfTheDigits() {
        assertEquals("20261019", filterDigits("2026-10-19"))
    }

    @Test
    fun filterDigitsTruncatesToTheMaximum() {
        assertEquals("123", filterDigits("123456", maxDigits = 3))
    }

    @Test
    fun filterDigitsCountsDigitsNotCharacters() {
        // "1a2b3" is five characters but three digits, so the third digit is still within
        // the limit of three.
        assertEquals("123", filterDigits("1a2b3", maxDigits = 3))
    }

    @Test
    fun filterDigitsKeepsEverythingWhenNoLimitIsGiven() {
        assertEquals("123456789", filterDigits("123456789"))
    }

    @Test
    fun withinLengthAcceptsTheLimit() {
        assertTrue(isWithinLength("abc", maxLength = 3))
    }

    @Test
    fun withinLengthRejectsOneCharacterTooMany() {
        assertFalse(isWithinLength("abcd", maxLength = 3))
    }

    @Test
    fun withinLengthAcceptsAnEmptyValue() {
        assertTrue(isWithinLength("", maxLength = 3))
    }

    @Test
    fun recurrenceIntervalAcceptsAPositiveWholeNumber() {
        assertTrue(isValidRecurrenceInterval("1"))
        assertTrue(isValidRecurrenceInterval("14"))
    }

    @Test
    fun recurrenceIntervalRejectsZero() {
        assertFalse(isValidRecurrenceInterval("0"))
    }

    @Test
    fun recurrenceIntervalRejectsANegativeNumber() {
        assertFalse(isValidRecurrenceInterval("-3"))
    }

    @Test
    fun recurrenceIntervalRejectsText() {
        assertFalse(isValidRecurrenceInterval("every week"))
    }

    @Test
    fun recurrenceIntervalRejectsAnEmptyValue() {
        assertFalse(isValidRecurrenceInterval(""))
    }

    @Test
    fun dayOfMonthAcceptsTheFirstAndTheLast() {
        assertTrue(isValidDayOfMonth("1"))
        assertTrue(isValidDayOfMonth("31"))
    }

    @Test
    fun dayOfMonthRejectsZero() {
        assertFalse(isValidDayOfMonth("0"))
    }

    @Test
    fun dayOfMonthRejectsThe32nd() {
        assertFalse(isValidDayOfMonth("32"))
    }

    @Test
    fun dayOfMonthRejectsText() {
        assertFalse(isValidDayOfMonth("monday"))
    }

    @Test
    fun filterRecurrenceIntervalDropsTheNonDigitsInsteadOfRejectingThem() {
        // The digit filter runs first, so the range check sees "3" and not "-3":
        // a stray sign costs the user nothing.
        assertEquals("3", filterRecurrenceInterval("-3"))
    }

    @Test
    fun filterRecurrenceIntervalTruncatesToTwoDigits() {
        assertEquals("10", filterRecurrenceInterval("100"))
    }

    @Test
    fun filterRecurrenceIntervalRejectsAValueThatIsNotAPositiveNumber() {
        assertNull(filterRecurrenceInterval("0"))
        assertNull(filterRecurrenceInterval(""))
    }

    @Test
    fun filterRecurrenceIntervalChecksTheRangeOnTheTruncatedText() {
        // "007" is seven, a valid interval, but the two-digit filter leaves "00" —
        // and zero is not an interval, so the edit is rejected.
        assertNull(filterRecurrenceInterval("007"))
    }

    @Test
    fun filterDayOfMonthDropsTheNonDigitsInsteadOfRejectingThem() {
        assertEquals("1", filterDayOfMonth("1a"))
    }

    @Test
    fun filterDayOfMonthTruncatesToTwoDigits() {
        assertEquals("19", filterDayOfMonth("199"))
    }

    @Test
    fun filterDayOfMonthRejectsThe32nd() {
        assertNull(filterDayOfMonth("32"))
    }

    @Test
    fun filterDayOfMonthChecksTheRangeOnTheTruncatedText() {
        // "0031" is the 31st, a valid day, but the two-digit filter leaves "00" —
        // and zero is not a day of the month, so the edit is rejected.
        assertNull(filterDayOfMonth("0031"))
    }
}
