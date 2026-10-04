package com.singularity.todo.feature.flows.pomodoro

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop mirror of `Maestro/flows/pomodoro/01-open-tab.yaml` and
 * `03-pause-resume.yaml`.
 *
 * Play and pause are the same control with a state-dependent tag — only one of
 * `TestTags.Pomodoro.PLAY_BUTTON` / `PAUSE_BUTTON` exists at any moment — so
 * asserting the tag swap is what proves the state actually changed, rather than
 * counting clicks.
 *
 * ## Why focus-task / skip / stop flows are not mirrored here
 *
 * The three flows below (`02-start-focus-task`, `04-skip-to-break`, `05-stop-resets`)
 * select a task from a chip list before operating the timer. That chip list comes
 * from `PomodoroTaskListProvider`, whose JVM binding (`JvmPomodoroTaskListProvider`)
 * returns an empty, never-updating flow — desktop has no inbox concept. There is
 * no chip to click, so these three flows have no desktop equivalent today.
 *
 * The timer itself (play/pause/skip/stop) is fully exercised by the two tests
 * below, which reach every control from the initial paused state without needing
 * a seeded fixture.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class PomodoroFlowTest {

    @Test
    fun timer_starts_paused_on_the_first_work_cycle() = runDesktopAppTest(checkA11y = true) {
        tapTab("Pomodoro")

        assertTagDisplayed(TestTags.Pomodoro.PHASE_LABEL)
        assertTextDisplayed("Work")
        assertTextDisplayed("Cycle 1")
        assertTagDisplayed(TestTags.Pomodoro.TIMER_LABEL)
        assertTagDisplayed(TestTags.Pomodoro.STOP_BUTTON)
        assertTagDisplayed(TestTags.Pomodoro.SKIP_BUTTON)

        // Paused, so the control offers to resume. Both assertions are atomic:
        // the tags live in the same `when` branch of the same composable.
        assertTagDisplayed(TestTags.Pomodoro.PLAY_BUTTON)
        assertTagNotExists(TestTags.Pomodoro.PAUSE_BUTTON)
    }

    @Test
    fun play_and_pause_swap_the_single_control() = runDesktopAppTest(checkA11y = true) {
        tapTab("Pomodoro")
        clickTag(TestTags.Pomodoro.PLAY_BUTTON)

        // Running, so the same control now offers to pause. The swap lands
        // asynchronously, so arrival waits (assertTagDisplayed) and departure
        // waits too (awaitTagGone).
        assertTagDisplayed(TestTags.Pomodoro.PAUSE_BUTTON)
        awaitTagGone(TestTags.Pomodoro.PLAY_BUTTON)
        assertTextDisplayed("Work")

        clickTag(TestTags.Pomodoro.PAUSE_BUTTON)

        assertTagDisplayed(TestTags.Pomodoro.PLAY_BUTTON)
        awaitTagGone(TestTags.Pomodoro.PAUSE_BUTTON)
        assertTextDisplayed("Work")
    }
}
