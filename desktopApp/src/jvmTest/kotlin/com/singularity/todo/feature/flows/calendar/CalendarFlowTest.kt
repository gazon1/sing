package com.singularity.todo.feature.flows.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.LocalDate
import org.junit.Test

/**
 * Desktop mirror of the three `Maestro/flows/calendar/` regression flows.
 *
 * The Android flows drive the calendar through a bottom-nav tab; desktop reaches
 * it through the drawer.
 *
 * Dates are computed from [todayInSystemZone] rather than hardcoded. The Maestro
 * flows lean on a `runScript` date helper for the same reason, and the
 * `singularity-todo-test-flaky-prevention` skill makes it a project rule: a
 * literal "September 2026" would silently start failing next month.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarFlowTest {

    private val today: LocalDate = todayInSystemZone()

    /** `"September 2026"` — the month header format the app renders. */
    private val monthTitle: String =
        "${today.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${today.year}"

    @Test
    fun month_view_renders_the_current_month() = runDesktopAppTest(checkA11y = true) {
        tapTab("Calendar")

        onNodeWithText(monthTitle).assertIsDisplayed()
        onNodeWithText("Mon").assertIsDisplayed()
        onNodeWithText("Sun").assertIsDisplayed()
        onNodeWithText("Month").assertIsDisplayed()
    }

    @Test
    fun every_day_of_the_month_has_an_addressable_cell() = runDesktopAppTest(checkA11y = true) {
        tapTab("Calendar")

        // The month view is a pager that also composes the neighbouring months,
        // and those pad with leading/trailing days — so a date near the end of
        // the month (today, the 30th) legitimately matches two cells, one of them
        // on the off-screen next-month page. Index 0 is the cell on screen.
        val midMonth = LocalDate(today.year, today.month, 15)
        onAllNodesWithTag(TestTags.calendarDay(midMonth.toString()))[0].assertIsDisplayed()
        onAllNodesWithTag(TestTags.calendarDay(today.toString()))[0].assertIsDisplayed()
    }

    @Test
    fun view_mode_switches_from_month_to_week() = runDesktopAppTest(checkA11y = true) {
        tapTab("Calendar")
        onNodeWithText("Month").assertIsDisplayed()
        onNodeWithText(monthTitle).assertIsDisplayed()

        // The control is labelled with the current mode and opens the switcher.
        onNodeWithText("Month").performClick()
        onNodeWithText("Week").performClick()

        // Week replaces the month title with a date range. The weekday columns
        // stay in both views, so the header is what distinguishes them.
        onNodeWithText("Week").assertIsDisplayed()
        onNodeWithText(monthTitle).assertDoesNotExist()
    }
}
