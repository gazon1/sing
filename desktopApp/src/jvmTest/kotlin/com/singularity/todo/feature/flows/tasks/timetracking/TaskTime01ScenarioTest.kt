package com.singularity.todo.feature.flows.tasks.timetracking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.test.helpers.assertTagDisplayed
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
 * Click Start on the time-tracking chip and assert the surface reacted. The
 * reaction is the Start → Stop swap: the write succeeds under the harness's
 * anonymous session (its `scopedUserId` is a real id the DAO accepts, so
 * `startEntry` upserts and the slot goes Running).
 *
 * An earlier version of this probe asserted the `ERROR` node instead, on the
 * premise that "the desktop harness session is Anonymous, startEntry cannot
 * succeed there" (e7866d15). The repository has never had an anonymous guard —
 * nothing in `TimeTrackingRepositoryImpl` looks at the session — so the write
 * succeeded, the chip swapped to Stop, and the probe timed out waiting for a
 * refusal that was never going to come. The refusal path it was really after
 * is covered by `TaskTimeSlotFailureTest` (a failing repo fake → Error state),
 * which is where a write failure belongs: the desktop probe answers "is the
 * surface reachable and wired", not "does a database error render".
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
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
        assertTagDisplayed(TestTags.taskItem(TASK_TITLE))
        awaitTag(TestTags.taskItem(TASK_TITLE)).performClick()

        // The time-tracking section renders in Idle (no entries yet) and shows
        // a Start chip. Click it — the write succeeds under the harness session,
        // and the slot swaps the chip to Stop.
        assertTagDisplayed(TestTags.TimeTracking.START)
        awaitTag(TestTags.TimeTracking.START).performClick()

        // The click must reach the repository, not vanish: a swallowed write
        // leaves the chip on Start forever (#196's original finding, which the
        // Error state exists to prevent — here the write succeeds, so the
        // evidence is the swap).
        assertTagDisplayed(TestTags.TimeTracking.STOP)
    }

    private companion object {
        const val TASK_TITLE = "Track me for carrier"

        /** Midday UTC on a Saturday — no timezone reaches a midnight boundary. */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
        val TEST_ZONE: TimeZone = TimeZone.UTC
    }
}
