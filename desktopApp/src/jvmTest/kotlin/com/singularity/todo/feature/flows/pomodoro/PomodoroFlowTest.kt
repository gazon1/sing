package com.singularity.todo.feature.flows.pomodoro

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.Test

/**
 * Desktop mirror of `Maestro/flows/pomodoro/01-open-tab.yaml` and
 * `03-pause-resume.yaml`.
 *
 * Play and pause are the same control with a state-dependent tag — only one of
 * `TestTags.Pomodoro.PLAY_BUTTON` / `PAUSE_BUTTON` exists at any moment — so
 * asserting the tag swap is what proves the state actually changed, rather than
 * counting clicks.
 *
 * Unlike the Android pause/resume flow, this one cannot drive the timer from a
 * task chip: the chip list comes from `PomodoroTaskListProvider`, and seeding it
 * means overriding a platform binding, which is out of scope for a flow suite
 * that otherwise runs against the real graph. Play/pause is still reachable from
 * the initial state, so the transition is covered without the fixture.
 */
@OptIn(ExperimentalTestApi::class)
class PomodoroFlowTest {

    @Test
    fun timer_starts_paused_on_the_first_work_cycle() = runDesktopAppTest(checkA11y = true) {
        tapTab("Pomodoro")

        onNodeWithTag(TestTags.Pomodoro.PHASE_LABEL).assertIsDisplayed()
        onNodeWithText("Work").assertIsDisplayed()
        onNodeWithText("Cycle 1").assertIsDisplayed()
        onNodeWithTag(TestTags.Pomodoro.TIMER_LABEL).assertIsDisplayed()
        onNodeWithTag(TestTags.Pomodoro.STOP_BUTTON).assertIsDisplayed()
        onNodeWithTag(TestTags.Pomodoro.SKIP_BUTTON).assertIsDisplayed()

        // Paused, so the control offers to resume.
        onNodeWithTag(TestTags.Pomodoro.PLAY_BUTTON).assertIsDisplayed()
        onNodeWithTag(TestTags.Pomodoro.PAUSE_BUTTON).assertDoesNotExist()
    }

    @Test
    fun play_and_pause_swap_the_single_control() = runDesktopAppTest(checkA11y = true) {
        tapTab("Pomodoro")
        onNodeWithTag(TestTags.Pomodoro.PLAY_BUTTON).performClick()

        // Running, so the same control now offers to pause.
        onNodeWithTag(TestTags.Pomodoro.PAUSE_BUTTON).assertIsDisplayed()
        onNodeWithTag(TestTags.Pomodoro.PLAY_BUTTON).assertDoesNotExist()
        onNodeWithText("Work").assertIsDisplayed()

        onNodeWithTag(TestTags.Pomodoro.PAUSE_BUTTON).performClick()

        onNodeWithTag(TestTags.Pomodoro.PLAY_BUTTON).assertIsDisplayed()
        onNodeWithTag(TestTags.Pomodoro.PAUSE_BUTTON).assertDoesNotExist()
        onNodeWithText("Work").assertIsDisplayed()
    }
}
