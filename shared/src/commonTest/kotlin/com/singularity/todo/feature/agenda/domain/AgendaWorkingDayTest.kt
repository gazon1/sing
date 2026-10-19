package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.Tag

/**
 * Tests for [AgendaPresets.forToday] — the decision of whether the work-day
 * preset applies today.
 *
 * The two switches in Settings live under the heading "Working Days", so
 * `saturdayIsWorkingDay = true` means Saturday *is* a working day. The
 * persisted fields are named `weekendSat` / `weekendSun`, which reads the
 * other way round; the setting screen is the authority on the meaning, and
 * this test pins the behaviour the screen promises.
 *
 * The bug this prevents is the one the dead-settings gate was built to find:
 * both switches were persisted, rendered, and read by nobody — the work-day
 * agenda showed on a Saturday whether or not the user had marked Saturday as
 * a working day.
 */
@Tag("fast")
class AgendaWorkingDayTest {

    private val workDay = AgendaPresets.workDayPreset(
        dayStartMinutes = 540,
        dayEndMinutes = 1020,
        lunchStartMinutes = 720,
        lunchEndMinutes = 780,
    )

    private fun forToday(
        dayOfWeek: DayOfWeek,
        saturdayIsWorkingDay: Boolean = false,
        sundayIsWorkingDay: Boolean = false,
    ) = AgendaPresets.forToday(
        dayOfWeek = dayOfWeek,
        saturdayIsWorkingDay = saturdayIsWorkingDay,
        sundayIsWorkingDay = sundayIsWorkingDay,
        workDay = workDay,
    )

    @Test
    fun `a weekday uses the work-day preset whatever the switches say`() {
        for (day in listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )) {
            assertSame(
                workDay,
                forToday(day),
                "$day is a working day by default",
            )
        }
    }

    @Test
    fun `saturday that is not a working day uses the plain today preset`() {
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.SATURDAY, saturdayIsWorkingDay = false),
        )
    }

    @Test
    fun `saturday marked as a working day uses the work-day preset`() {
        assertSame(
            workDay,
            forToday(DayOfWeek.SATURDAY, saturdayIsWorkingDay = true),
        )
    }

    @Test
    fun `sunday that is not a working day uses the plain today preset`() {
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.SUNDAY, sundayIsWorkingDay = false),
        )
    }

    @Test
    fun `sunday marked as a working day uses the work-day preset`() {
        assertSame(
            workDay,
            forToday(DayOfWeek.SUNDAY, sundayIsWorkingDay = true),
        )
    }

    @Test
    fun `the saturday switch does not decide sunday`() {
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.SUNDAY, saturdayIsWorkingDay = true),
            "Saturday being a working day says nothing about Sunday",
        )
    }

    @Test
    fun `the sunday switch does not decide saturday`() {
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.SATURDAY, sundayIsWorkingDay = true),
            "Sunday being a working day says nothing about Saturday",
        )
    }

    @Test
    fun `the work-day preset is untouched by the decision`() {
        val before = workDay.sections.size
        forToday(DayOfWeek.SATURDAY)
        assertEquals(before, workDay.sections.size)
    }
}
