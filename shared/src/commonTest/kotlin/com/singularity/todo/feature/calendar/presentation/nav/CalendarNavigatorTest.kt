package com.singularity.todo.feature.calendar.presentation.nav

import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.nav.CalendarRoute
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the calendar has to go back to.
 *
 * The calendar's own stack holds the month the tab opens on, and a nested route
 * — a day — is pushed on top of it. The root is where the tab starts, so there
 * is nothing behind it: the Back arrow the calendar publishes on that screen
 * promised a step back the root has no meaning for (#3093). A nested route does
 * have the root behind it, and its arrow returns to it.
 */
@Tag("fast")
class CalendarNavigatorTest {
    private fun navigator() = CalendarNavigator(NavBackStack(CalendarRoute.Month("2026-10-01"))) {}

    @Test
    fun `the calendar root has nowhere to go back to`() {
        assertFalse(
            navigator().canGoBack(),
            "the root is where the tab starts: there is nothing behind it, so it publishes " +
                "no Back arrow",
        )
    }

    @Test
    fun `a nested day has the root to go back to`() {
        val navigator = navigator()
        navigator.openDay(LocalDate(2026, 10, 12))

        assertTrue(
            navigator.canGoBack(),
            "a day is pushed on top of the root, and the arrow that returns to the root " +
                "must stay",
        )
    }
}
