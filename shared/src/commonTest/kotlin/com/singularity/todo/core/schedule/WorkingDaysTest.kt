package com.singularity.todo.core.schedule

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * Tests for [WorkingDays] — the set of days the user works on, how it is
 * stored, and what the two legacy booleans meant.
 *
 * The legacy booleans are named `weekendSat` / `weekendSun`, which reads the
 * other way round from the switch under the "Working Days" heading: `true`
 * means the day *is* a working day. The screen was the authority on that, so
 * the migration follows the screen and not the field name — a migration that
 * inverted the meaning would move every weekend worker to the wrong week.
 */
@Tag("fast")
class WorkingDaysTest {

    @Test
    fun `the default week is Monday to Friday`() {
        assertEquals(
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            ),
            WorkingDays.DEFAULT,
        )
    }

    @Test
    fun `a set round-trips through storage`() {
        val days = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.SUNDAY,
        )
        assertEquals(days, WorkingDays.fromStorageString(WorkingDays.toStorageString(days)))
    }

    @Test
    fun `storage is the ISO day numbers in order`() {
        assertEquals(
            "1,3,7",
            WorkingDays.toStorageString(
                setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            ),
        )
    }

    @Test
    fun `the empty set is stored as an empty string and read back as empty`() {
        assertEquals("", WorkingDays.toStorageString(emptySet()))
        assertEquals(emptySet(), WorkingDays.fromStorageString(""))
    }

    @Test
    fun `an unknown day number is dropped rather than fatal`() {
        // A value written by a future version must not make the settings
        // screen unopenable.
        assertEquals(
            setOf(DayOfWeek.MONDAY),
            WorkingDays.fromStorageString("1,9,42"),
        )
    }

    @Test
    fun `garbage in storage reads as no days`() {
        assertEquals(emptySet(), WorkingDays.fromStorageString("not-a-day"))
    }

    @Test
    fun `the legacy flags say whether the weekend day is a working day`() {
        assertEquals(
            WorkingDays.DEFAULT,
            WorkingDays.fromLegacyFlags(saturdayIsWorkingDay = false, sundayIsWorkingDay = false),
        )
        assertEquals(
            WorkingDays.DEFAULT + DayOfWeek.SATURDAY,
            WorkingDays.fromLegacyFlags(saturdayIsWorkingDay = true, sundayIsWorkingDay = false),
        )
        assertEquals(
            WorkingDays.DEFAULT + DayOfWeek.SUNDAY,
            WorkingDays.fromLegacyFlags(saturdayIsWorkingDay = false, sundayIsWorkingDay = true),
        )
        assertEquals(
            WorkingDays.DEFAULT + setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            WorkingDays.fromLegacyFlags(saturdayIsWorkingDay = true, sundayIsWorkingDay = true),
        )
    }

    @Test
    fun `the legacy flags are a projection of the set`() {
        val (saturday, sunday) = WorkingDays.toLegacyFlags(
            WorkingDays.DEFAULT + DayOfWeek.SATURDAY,
        )
        assertTrue(saturday)
        assertEquals(false, sunday)
    }
}
