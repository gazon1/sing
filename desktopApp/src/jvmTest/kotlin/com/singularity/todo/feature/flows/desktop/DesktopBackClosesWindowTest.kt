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
import com.singularity.todo.test.helpers.assertTagNotExists
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
 * go back to.
 *
 * The policy itself is proved by `DesktopBackTest`, which drives the navigation state
 * directly. This test proves it through the real shell, because a policy nothing on
 * screen can reach is not a behaviour a user has.
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
 *
 * #3093 changed the root: the primary tab roots (Inbox, Today, Calendar) are
 * top-level destinations, not screens in a back stack, so they draw no back control
 * at all. The window's own close path is unchanged — the host's close request, which
 * `DesktopBackTest` still proves — but the root no longer offers a visible arrow for
 * it, and this file no longer claims one.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class DesktopBackClosesWindowTest {
    @Test
    fun a_primary_tab_root_draws_no_back_control() {
        runDesktopAppTest(clock = CLOCK) {
            // The shell drew its own chrome, so the FAB below is the shell's and not a
            // leftover node from a screen that never unmounted.
            assertContentDescriptionDisplayed(DesktopShell.FAB_ADD_TASK)

            // The start tab's root is a top-level destination, not a screen in a back
            // stack, so it draws no back control (#3093). The window's close path is
            // unchanged — it is the host's close request, proved by `DesktopBackTest` —
            // but the root no longer offers a visible arrow for it.
            assertTagNotExists(TestTags.SHELL_BACK_BUTTON)
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
            // The root is a top-level destination and draws no back control of its
            // own (#3093).
            assertEquals(
                0,
                backControlCount(),
                "the root draws no back control",
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
