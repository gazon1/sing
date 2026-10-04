package com.singularity.todo.feature.flows.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.awaitAnyDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test

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
@Tag("fast")
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
        // and those pad with leading/trailing days — so a date near *either*
        // edge of the month matches two cells: the 30th pads into the next
        // month's page, the 1st into the previous one. Which match is index 0
        // follows the pager's composition order, not what is on screen, and
        // silently flips over at midnight on the 1st — this test passed on
        // September 30th and failed on October 1st for exactly that reason.
        // The intent is "the day has an addressable, visible cell", so assert
        // that some matching cell is on screen instead of pinning an index.
        val midMonth = LocalDate(today.year, today.month, 15)
        awaitAnyDisplayed(TestTags.calendarDay(midMonth.toString()))
        awaitAnyDisplayed(TestTags.calendarDay(today.toString()))
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
