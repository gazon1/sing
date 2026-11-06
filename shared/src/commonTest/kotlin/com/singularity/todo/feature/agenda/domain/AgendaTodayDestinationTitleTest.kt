package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Today destination's header is the name of the tab it lives under.
 *
 * The destination evaluates the work-day preset on a working day and the plain
 * [AgendaPresets.Today] preset otherwise. Both have to publish the same title,
 * or the shell bar renames the screen at the weekend and disagrees with the tab
 * underneath it.
 */
@Tag("fast")
class AgendaTodayDestinationTitleTest {
    private val workDay =
        AgendaPresets.workDayPreset(
            dayStartMinutes = 9 * 60,
            dayEndMinutes = 17 * 60,
            lunchStartMinutes = 12 * 60,
            lunchEndMinutes = 13 * 60,
        )

    private val workingDays =
        setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )

    @Test
    fun `the today header title is the tab name on a working day and on a day off`() {
        val workingDay =
            AgendaPresets.forToday(
                dayOfWeek = DayOfWeek.MONDAY,
                workingDays = workingDays,
                workDay = workDay,
            )
        val dayOff =
            AgendaPresets.forToday(
                dayOfWeek = DayOfWeek.SATURDAY,
                workingDays = workingDays,
                workDay = workDay,
            )

        // The tab's label and the header's title are one string: the Today
        // destination's own name, wherever the shell reads it from.
        val tabLabel = AppDestination.AgendaGraph(AgendaStartRoute.Today).title
        assertEquals(tabLabel, workingDay.title)
        assertEquals(tabLabel, dayOff.title)
        assertEquals(workingDay.title, dayOff.title)
    }

    @Test
    fun `the other agenda destinations keep their own header titles`() {
        assertEquals("Inbox", AgendaPresets.Inbox.title)
        assertEquals("Upcoming", AgendaPresets.Upcoming.title)
    }
}
