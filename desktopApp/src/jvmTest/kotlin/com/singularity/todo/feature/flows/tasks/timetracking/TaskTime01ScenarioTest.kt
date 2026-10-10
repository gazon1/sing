package com.singularity.todo.feature.flows.tasks.timetracking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop automation for scenario `TASK-TIME-01` — see
 * `infra/kiwi/scenarios/tasks/timetracking/TASK-TIME-01.yaml`.
 *
 * The scenario claims: android, desktop.
 *
 * This file is a **reachability probe**, not the scenario's assertion: it
 * answers one question — can this tier reach the node at all — and the
 * answer is yours to interpret.
 *
 * ## Read this before believing a failure
 *
 * A failed probe means **the node is not in the tree you just rendered**. It does
 * not mean the feature is Android-only, and narrowing the spec to one target on
 * the strength of it is how `TASK-TIME-01` was made wrong on 2026-10-05: the whole
 * feature was in `commonMain` and the desktop screen simply was not rendering it,
 * because a merge (`f2a87c7c`) had put the two platform graphs on different
 * screens.
 *
 * Before concluding anything, check where the feature lives:
 *
 *     find shared/src/commonMain -iname '*timetracking*'
 *
 * If it is in `commonMain`, a missing node is a **hole in the code**, not a
 * property of the platform. Fix the code and keep both targets claimed. Only a
 * structural difference justifies narrowing — `ModalBottomSheet` on desktop, which
 * is a separate *window* and whose tree is byte-identical before and after a click.
 *
 * ## The linkage
 *
 * The `@DisplayName` id must stay the **first token**. JUnit writes the
 * display name into `<testcase name>`, and the scanner joins on exactly
 * that, so an id anywhere else validates and then joins nothing.
 *
 * `@Tag("slow")` is not optional: without it the class is excluded from
 * the default local cycle and the scenario reads as never run forever.
 *
 * ## What this probe measures
 *
 * The desktop harness runs as `Anonymous`. `startEntry` cannot succeed in an
 * anonymous session — the write is refused at the repository layer. Before
 * the fix for #196 the refusal was silent: the chip sat on Start and a click
 * that did nothing was indistinguishable from a control that was never wired.
 * The `Error` state now surfaces the refusal, so the probe asserts the
 * `ERROR` node is reachable after clicking Start. A successful swap
 * (Start → Stop) requires a signed-in session, which is a harness capability,
 * not a test.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TaskTime01ScenarioTest {

    @Test
    @DisplayName("TASK-TIME-01 probe: the surface is reachable")
    fun probe_the_surface_is_reachable() = runDesktopAppTest(
        clock = TEST_CLOCK,
        checkA11y = true,
    ) { koin ->
        // Create a task and navigate to its detail via the Today tab.
        tasks(koin).given(
            due = todayAt(TEST_CLOCK, TEST_ZONE),
            title = TASK_TITLE,
        )

        tapTab("Today")
        awaitTag(TestTags.taskItem(TASK_TITLE)).assertIsDisplayed()
        awaitTag(TestTags.taskItem(TASK_TITLE)).performClick()

        // The time-tracking section renders in Idle (no entries yet) and shows
        // a Start chip. Click it — the anonymous session refuses the write,
        // and the Error state renders the refusal in place of the chip.
        awaitTag(TestTags.TimeTracking.START).assertIsDisplayed()
        awaitTag(TestTags.TimeTracking.START).performClick()

        // The refusal must surface as a state, not silence (#196).
        // Without the fix the chip simply stays on Start; with the fix the
        // ERROR node appears and this assertion passes.
        awaitTag(TestTags.TimeTracking.ERROR).assertIsDisplayed()
    }

    private companion object {
        const val TASK_TITLE = "Track me for carrier"

        /** Midday UTC on a Saturday — no timezone reaches a midnight boundary. */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
        val TEST_ZONE: TimeZone = TimeZone.UTC
    }
}
