package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
            onNodeWithContentDescription(DesktopShell.FAB_ADD_TASK).performClick()

            repeat(8) { frame ->
                mainClock.advanceTimeByFrame()
                val controls = backControlCount()
                assertTrue(
                    controls <= 1,
                    "frame $frame drew $controls back controls: the shell's and the screen's never stack",
                )
            }

            assertEquals(
                1,
                backControlCount(),
                "the screen on top draws the one back control",
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
