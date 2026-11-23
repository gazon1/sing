package com.singularity.todo.feature.calendar.presentation.screen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalScreenActions
import com.singularity.todo.core.ui.components.ScreenActionsHolder
import com.singularity.todo.feature.calendar.presentation.nav.CalendarNavigator
import com.singularity.todo.feature.calendar.presentation.nav.LocalCalendarNavigator
import com.singularity.todo.feature.nav.CalendarRoute
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.buildDesktopAppKoin
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.compose.KoinIsolatedContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What the calendar publishes to the shell bar, and what it draws itself.
 *
 * On Android the shell owns the only bar, so the calendar publishes to it
 * instead of drawing a second one — the month it shows as the title, and a
 * Back arrow when there is somewhere to go back to. The publication is what
 * the shell draws in its own bar, so what the calendar publishes *is* what
 * the user sees.
 *
 * On the desktop there is no shell bar to publish to: the calendar draws its
 * own bar, and its own arrow with it. The arrow belongs to the calendar — the
 * day sits on the calendar's stack, and the shell's arrow pops the shell's.
 *
 * The calendar's root is where its tab starts: the stack holds the month and
 * nothing else, and there is nothing behind it to go back to. A Back arrow
 * there promised a step the root has no meaning for (#3093), so the root
 * publishes no icon and draws no arrow of its own. A nested route — a
 * day — is pushed on top of the root and keeps the arrow that returns to it.
 *
 * The screen is mounted, not the bar alone: the decision of *what* to publish
 * is the screen's, and a test that mounted the bar itself would assert its own
 * input back at itself.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class CalendarScreenBackTest {
    private val anchor = LocalDate(2026, 10, 1)

    @Test
    @DisplayName("#3093 the calendar root publishes no Back icon")
    fun the_calendar_root_publishes_no_back_icon() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val navigator =
                CalendarNavigator(NavBackStack(CalendarRoute.Month(anchor.toString()))) {}

            mount(holder, navigator)

            // The bar published its month: the screen is loaded and the bar is
            // composed. Without this the assertion below would pass on a screen
            // that published nothing at all.
            waitUntil("the calendar publishes its month to the shell bar") {
                holder.title != null
            }

            assertNull(
                holder.navigationIcon,
                "the calendar's root is where the tab starts: there is nothing behind it to " +
                    "go back to, so it must publish no navigation icon for the shell to draw",
            )
            assertTagNotExists(TestTags.TOP_BAR_BACK_BUTTON)
        }

    @Test
    @DisplayName("#3093 a nested calendar route keeps its Back icon")
    fun a_nested_calendar_route_keeps_its_back_icon() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val navigator =
                CalendarNavigator(NavBackStack(CalendarRoute.Month(anchor.toString()))) {}
            navigator.openDay(LocalDate(2026, 10, 12))

            mount(holder, navigator)

            waitUntil("the calendar publishes its month to the shell bar") {
                holder.title != null
            }

            assertNotNull(
                holder.navigationIcon,
                "a day is pushed on top of the calendar's root, and the arrow that returns " +
                    "to the root must still be published",
            )
        }

    @Test
    @DisplayName("#3093 the calendar root draws no Back arrow of its own")
    fun the_calendar_root_draws_no_back_arrow_of_its_own() =
        runIsolatedComposeTest {
            val navigator =
                CalendarNavigator(NavBackStack<CalendarRoute>(CalendarRoute.Month(anchor.toString()))) {}

            mountOnDesktop(navigator)

            // The calendar's root is where the tab starts: the bar draws no arrow
            // of its own there.
            assertTagNotExists(TestTags.TOP_BAR_BACK_BUTTON)
        }

    @Test
    @DisplayName("#3093 a nested calendar route draws a Back arrow that returns to the root")
    fun a_nested_calendar_route_draws_a_back_arrow_that_returns_to_the_root() =
        runIsolatedComposeTest {
            val stack = NavBackStack<CalendarRoute>(CalendarRoute.Month(anchor.toString()))
            val navigator = CalendarNavigator(stack) {}
            navigator.openDay(LocalDate(2026, 10, 12))

            mountOnDesktop(navigator)

            // A day is pushed on top of the calendar's root, and the arrow that
            // returns to the root has to be on screen.
            assertTagDisplayed(TestTags.TOP_BAR_BACK_BUTTON).performClick()
            waitForIdle()
            assertEquals(
                1,
                stack.size,
                "the arrow returns to the calendar's own root — the shell's arrow would pop " +
                    "the shell's stack instead",
            )
        }

    /**
     * The calendar screen, with the shell bar's holder and the calendar's own
     * navigator provided the way the app provides them.
     */
    private fun DesktopComposeUiTest.mount(
        holder: ScreenActionsHolder,
        navigator: CalendarNavigator,
    ) {
        setContent {
            KoinIsolatedContext(buildDesktopAppKoin(clock = CLOCK)) {
                CompositionLocalProvider(
                    LocalScreenActions provides holder,
                    LocalCalendarNavigator provides navigator,
                ) {
                    CalendarScreen(anchorDate = anchor)
                }
            }
        }
        waitForIdle()
    }

    /**
     * The calendar screen as the desktop shell has it: no shell bar to publish
     * to, so the calendar draws its own bar — and its own back arrow.
     */
    private fun DesktopComposeUiTest.mountOnDesktop(navigator: CalendarNavigator) {
        setContent {
            KoinIsolatedContext(buildDesktopAppKoin(clock = CLOCK)) {
                CompositionLocalProvider(
                    LocalCalendarNavigator provides navigator,
                ) {
                    CalendarScreen(anchorDate = anchor)
                }
            }
        }
        waitForIdle()
        waitUntil("the calendar draws its own bar") {
            onAllNodes(hasTestTag(TestTags.CALENDAR_MONTH_TITLE)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-10-12T10:00:00Z"))
    }
}
