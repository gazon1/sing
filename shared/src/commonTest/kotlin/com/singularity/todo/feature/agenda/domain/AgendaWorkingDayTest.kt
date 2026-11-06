package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.schedule.WorkingDays
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Tests for [AgendaPresets.forToday] — the decision of whether the work-day
 * preset applies today.
 *
 * The setting is the set of days the user works on: the "Working Days"
 * section in Settings renders the whole week, and the agenda asks the same
 * question the section asks — "is today one of the days you work on?".
 *
 * The bug this prevents is the one the dead-settings gate was built to find:
 * the switches were persisted, rendered, and read by nobody — the work-day
 * agenda showed on a Saturday whether or not the user had marked Saturday as
 * a working day.
 */
@Tag("fast")
class AgendaWorkingDayTest {
    private val workDay =
        AgendaPresets.workDayPreset(
            dayStartMinutes = 540,
            dayEndMinutes = 1020,
            lunchStartMinutes = 720,
            lunchEndMinutes = 780,
        )

    private fun forToday(
        dayOfWeek: DayOfWeek,
        workingDays: Set<DayOfWeek> = WorkingDays.DEFAULT,
    ) = AgendaPresets.forToday(
        dayOfWeek = dayOfWeek,
        workingDays = workingDays,
        workDay = workDay,
    )

    @Test
    fun `a weekday uses the work-day preset when the week says so`() {
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
    fun `a day the week does not contain uses the plain today preset`() {
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.SATURDAY, workingDays = WorkingDays.DEFAULT),
        )
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.MONDAY, workingDays = setOf(DayOfWeek.SUNDAY)),
        )
    }

    @Test
    fun `saturday that is a working day uses the work-day preset`() {
        assertSame(
            workDay,
            forToday(
                DayOfWeek.SATURDAY,
                workingDays = WorkingDays.DEFAULT + DayOfWeek.SATURDAY,
            ),
        )
    }

    @Test
    fun `sunday that is a working day uses the work-day preset`() {
        assertSame(
            workDay,
            forToday(
                DayOfWeek.SUNDAY,
                workingDays = WorkingDays.DEFAULT + DayOfWeek.SUNDAY,
            ),
        )
    }

    @Test
    fun `one day in the set does not decide another`() {
        assertSame(
            AgendaPresets.Today,
            forToday(
                DayOfWeek.SUNDAY,
                workingDays = WorkingDays.DEFAULT + DayOfWeek.SATURDAY,
            ),
            "Saturday being a working day says nothing about Sunday",
        )
        assertSame(
            AgendaPresets.Today,
            forToday(
                DayOfWeek.SATURDAY,
                workingDays = WorkingDays.DEFAULT + DayOfWeek.SUNDAY,
            ),
            "Sunday being a working day says nothing about Saturday",
        )
    }

    @Test
    fun `a week without weekdays is honoured`() {
        // A user whose working week is only the weekend: the weekdays are days
        // off, and the agenda must say so.
        val weekendOnly = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        assertSame(AgendaPresets.Today, forToday(DayOfWeek.MONDAY, weekendOnly))
        assertSame(workDay, forToday(DayOfWeek.SATURDAY, weekendOnly))
    }

    @Test
    fun `a week with no days at all is honoured`() {
        assertSame(
            AgendaPresets.Today,
            forToday(DayOfWeek.MONDAY, workingDays = emptySet()),
        )
    }

    @Test
    fun `the work-day preset is untouched by the decision`() {
        val before = workDay.sections.size
        forToday(DayOfWeek.SATURDAY)
        assertEquals(before, workDay.sections.size)
    }

    /**
     * The header names the tab, on a working day and on a day off alike.
     *
     * The agenda draws the chosen definition's title, and the work-day definition
     * used to carry a name of its own ("Work Day"), so the same destination
     * renamed itself at the weekend and named something the bottom bar does not.
     */
    @Test
    fun `the today destination's header names the tab on a working day and on a day off`() {
        val tabLabel = AppDestination.AgendaGraph(AgendaStartRoute.Today).title

        assertEquals(
            tabLabel,
            forToday(DayOfWeek.WEDNESDAY).title,
            "on a working day the header must name the tab the user is looking at",
        )
        assertEquals(
            tabLabel,
            forToday(DayOfWeek.SATURDAY).title,
            "on a day off the header must name the same tab",
        )
    }

    @Test
    fun `the other agenda destinations still name their tab`() {
        assertEquals(
            AppDestination.AgendaGraph(AgendaStartRoute.Inbox).title,
            AgendaPresets.Inbox.title,
        )
        assertEquals(
            AppDestination.AgendaGraph(AgendaStartRoute.Upcoming).title,
            AgendaPresets.Upcoming.title,
        )
    }
}
