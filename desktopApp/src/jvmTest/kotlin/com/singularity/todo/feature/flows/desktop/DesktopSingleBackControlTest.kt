package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.awaitContentDescription
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * #2287 — one back control, at every moment.
 *
 * The shell draws a back control of its own, and a screen that draws a back
 * arrow of its own already owns the gesture. The two controls answer the same
 * question, so both of them on screen is two controls for one meaning — and a
 * reader who presses one of them does not know which meaning they got.
 *
 * The check that matters is not the state at rest: a screen that draws its own
 * arrow settles with exactly one arrow on screen, but it gets there through a
 * handoff, and the handoff is where a second arrow used to show up for a frame.
 * So this test steps the clock frame by frame and counts the back controls in
 * every frame of the handoff.
 *
 * #3093 changed where the count happens, not the invariant: a primary tab root
 * draws no back control at all, so the handoff is watched where the shell's
 * control is on screen — the count is one control in every frame, and the two
 * that would mean "two controls for one meaning" are still the failure.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class DesktopSingleBackControlTest {
    @Test
    fun the_shell_and_the_screen_never_both_draw_a_back_arrow() {
        runDesktopAppTest(clock = CLOCK) {
            // The clock is under the test's control: the handoff is one frame
            // long, and a test that lets the clock run on its own would step
            // straight over it.
            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(500)

            // The FAB pushes the task editor, which draws a back arrow of its own.
            awaitContentDescription(DesktopShell.FAB_ADD_TASK).performClick()

            repeat(8) { frame ->
                mainClock.advanceTimeByFrame()
                val controls = backControlCount()
                assertEquals(
                    1,
                    controls,
                    "frame $frame drew $controls back controls: there is one control, and it is one",
                )
            }

            assertEquals(
                1,
                backControlCount(),
                "the screen on top draws the one back control",
            )
        }
    }

    /**
     * The other direction of the handoff: the screen that owns the control leaves.
     *
     * Entering is the easy half — the shell keeps its control until the screen on
     * top takes it. Leaving is the half where a control can go missing: the screen
     * is gone, and the shell's control comes back only after the release has
     * reached it. So this test counts the controls in every frame of the way back
     * as well.
     *
     * The way back lands on the Upcoming tab, which is not a primary root: the
     * shell's control is on screen there, and it has to come back when the screen
     * that owned the control leaves. Landing on the Inbox instead would prove
     * nothing about the way back — a primary root draws no control at all
     * (#3093), so the count would be zero however the handoff went.
     */
    @Test
    fun the_control_leaves_the_screen_without_a_second_one_taking_its_place() {
        runDesktopAppTest(clock = CLOCK) {
            // The tab switch happens with the clock running: the drawer slides in.
            tapTab("Upcoming")
            assertEquals(
                1,
                backControlCount(),
                "the shell draws its own control at a destination that is not a primary root",
            )

            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(500)

            // The FAB pushes the task editor, which draws a back arrow of its own.
            awaitContentDescription(DesktopShell.FAB_ADD_TASK).performClick()
            repeat(4) { mainClock.advanceTimeByFrame() }

            // The editor owns the control: its arrow is the one on screen, and the
            // shell's is not.
            assertEquals(1, backControlCount(), "the editor draws the one back control")

            // The editor's own arrow is what leaves the screen.
            onAllNodes(
                hasContentDescription(DesktopShell.BACK) and hasTestTag(TestTags.SHELL_BACK_BUTTON).not(),
            ).onFirst().performClick()

            repeat(8) { frame ->
                mainClock.advanceTimeByFrame()
                val controls = backControlCount()
                assertEquals(
                    1,
                    controls,
                    "frame $frame of the way back drew $controls back controls: the shell's " +
                        "control comes back, and there is one control, and it is one",
                )
            }

            assertEquals(
                1,
                backControlCount(),
                "the shell's own control is back on the screen below",
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
