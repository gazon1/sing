package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickScreenBack
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * #2287 — the Desktop shell's own back control: it pops while there is somewhere to
 * go back to, and at the ultimate root it takes the window's close path.
 *
 * The policy itself is proved by `DesktopBackTest`, which drives the navigation state
 * directly. This test proves it through the real shell, because a policy nothing on
 * screen can reach is not a behaviour a user has: the control has to be drawn at the
 * root, and the press there has to reach the host's close request.
 *
 * Both halves are mounted flows, because both halves are about the shell's chrome:
 * a request that never leaves the navigation state is not what a user presses.
 *
 * The third case is the one that keeps the control singular: a screen that draws a
 * back arrow of its own owns the gesture, so the shell's control stands down and the
 * two never stack into two arrows for one meaning.
 *
 * The second case is the other "somewhere to go back to": the tab that was active
 * before. A back there returns to that tab and closes nothing.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class DesktopBackClosesWindowTest {
    @Test
    fun a_back_at_the_ultimate_root_asks_the_host_to_close_the_window() {
        var closes = 0

        runDesktopAppTest(clock = CLOCK, onCloseRequest = { closes++ }) {
            // The shell drew its own chrome, so the FAB below is the shell's and not a
            // leftover node from a screen that never unmounted.
            assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)

            // At the root of the start tab nothing else draws a back arrow, so the
            // shell's control is the one on screen — the one that means "back" here.
            assertTagDisplayed(TestTags.SHELL_BACK_BUTTON)

            clickTag(TestTags.SHELL_BACK_BUTTON)

            assertEquals(
                1,
                closes,
                "a back with nothing left to pop is the window's own close",
            )
        }
    }

    @Test
    fun a_back_that_leaves_a_nested_screen_closes_nothing() {
        var closes = 0

        runDesktopAppTest(clock = CLOCK, onCloseRequest = { closes++ }) {
            // The FAB pushes the task editor on top of the start tab.
            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            // The editor draws a back arrow of its own, so the shell's control stands
            // down: one back control, drawn by the screen that is on top.
            awaitTagGone(TestTags.SHELL_BACK_BUTTON)

            clickScreenBack()

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

    @Test
    fun a_back_at_the_root_of_another_tab_returns_to_the_previous_tab() {
        var closes = 0

        runDesktopAppTest(clock = CLOCK, onCloseRequest = { closes++ }) {
            // Leave the start tab for another one: the tab that was active before
            // is now somewhere to go back to.
            tapTab("Upcoming")

            // The root of that tab draws no leading control of its own, so the
            // shell's control is the one on screen.
            assertTagDisplayed(TestTags.SHELL_BACK_BUTTON)

            clickTag(TestTags.SHELL_BACK_BUTTON)

            assertEquals(
                0,
                closes,
                "a back with a previous tab to return to pops, it does not close the window",
            )

            // The tab that was active before is the one on screen again.
            openDrawer()
            assertCurrentTab("Today")
        }
    }

    @Test
    fun the_shell_and_the_screen_do_not_both_draw_a_back_arrow() {
        runDesktopAppTest(clock = CLOCK) {
            // At the root the shell's control is the only back control.
            assertEquals(
                1,
                backControlCount(),
                "the root draws one back control",
            )

            clickContentDescription(DesktopShell.FAB_ADD_TASK)

            // The editor drew a back arrow of its own. The shell's control stood
            // down, so the screen still has exactly one — not the shell's arrow
            // stacked on top of the editor's.
            assertEquals(
                1,
                backControlCount(),
                "a screen that draws its own back arrow does not get a second one on top",
            )
        }
    }

    /** How many back controls are on screen right now. */
    private fun DesktopComposeUiTest.backControlCount(): Int =
        onAllNodes(hasContentDescription(DesktopShell.BACK)).fetchSemanticsNodes().size

    private companion object {
        /** Mid-month, so nothing in this file straddles a date boundary. */
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
