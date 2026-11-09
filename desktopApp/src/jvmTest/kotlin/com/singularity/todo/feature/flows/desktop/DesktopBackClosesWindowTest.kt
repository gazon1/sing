package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * #1523 — Back on Desktop closes the window only when there is nothing left to go back to.
 *
 * The positive half of that claim lives in `DesktopBackTest`, at the policy: nothing on
 * screen is a back control at the ultimate root of the start tab, so no mounted flow can
 * press one there. This test covers the other direction through the real shell — a back
 * the shell *can* show (the task editor's arrow) must return to the tab and leave the
 * window alone. Without it, routing every back through the close request would pass the
 * policy test and close the app on the first editor dismissal.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class DesktopBackClosesWindowTest {
    @Test
    fun leaving_the_task_editor_returns_to_the_tab_and_does_not_close_the_window() {
        var closes = 0

        runDesktopAppTest(clock = CLOCK, onCloseRequest = { closes++ }) {
            // The shell drew its own chrome, so the FAB below is the shell's and not a
            // leftover node from a screen that never unmounted.
            assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)

            // The FAB pushes the task editor on top of the start tab.
            clickContentDescription(DesktopShell.FAB_ADD_TASK)
            assertContentDescriptionDisplayed(DesktopShell.BACK)

            // Its own back arrow: one nested screen to pop, so goBack() answers true.
            clickContentDescription(DesktopShell.BACK)

            assertEquals(
                0,
                closes,
                "a back that pops a nested screen must not also close the window",
            )
            // Back on the tab: the shell's FAB is drawn again, which it is not while a
            // nested screen is on top.
            assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)
        }
    }

    private companion object {
        /** Mid-month, so nothing in this file straddles a date boundary. */
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
