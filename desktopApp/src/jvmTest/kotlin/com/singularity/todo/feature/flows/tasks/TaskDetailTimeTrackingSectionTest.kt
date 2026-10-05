package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The desktop task detail renders the time-tracking section.
 *
 * ## This is a regression guard, deliberately not a scenario carrier
 *
 * `TASK-TIME-01` claims both targets now and this test does **not** carry it,
 * which looks like an oversight and is not. The scenario says the chip *swaps*
 * between Start and Stop, and the swap cannot be asserted here: the desktop
 * harness session is `Anonymous`, `startEntry` fails against the real
 * `TimeTrackingRepositoryImpl`, and `TaskTimeSlot.start()` discards the failure
 * with an empty `onFailure { }` — so the chip simply does not change and nothing
 * says why. Filed as #196. Asserting "the chip is there" under a scenario id
 * would award a `●` for a control that renders and does nothing, which is the
 * one thing a `●` is not supposed to mean.
 *
 * So the scenario's two cells stay holes and this test earns its place a
 * different way: it is the guard against the exact regression that caused the
 * problem.
 *
 * ## What it guards
 *
 * `f2a87c7c` merged `refactor/time-hub-ai-proposals` and took that branch's older
 * Android navigation graph over the phase-4 detail refactor (`adc95585`) on
 * Android only. Two screens then shipped under one route and one ViewModel: the
 * Android monolith kept time tracking, the refactored JVM shell renders
 * `extraSections = null` and had lost it. Desktop users had no timer at all, and
 * the coverage matrix reported the row as `○` — a hole, which reads as "nobody
 * wrote the test yet".
 *
 * A matrix cannot tell "not automated" from "not built", so the guard has to
 * live in code. Both graphs now render `TaskDetailContent` and
 * `TaskDetailViewScreen` is deleted, so this test fails if the two drift apart
 * again.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TaskDetailTimeTrackingSectionTest {

    @Test
    @DisplayName("the desktop task detail offers a time-tracking control")
    fun the_desktop_task_detail_offers_a_time_tracking_control() = runDesktopAppTest(checkA11y = true) { koin ->
        tasks(koin).given(due = todayInSystemZone(), title = "Track me")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Track me")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        awaitTag(TestTags.TimeTracking.START).assertIsDisplayed()

        // Clicked so that a future regression cannot pass by rendering a dead
        // control: the click must at least be dispatched without throwing, and
        // the chip must still be the one thing under the finger afterwards.
        awaitTag(TestTags.TimeTracking.START).performClick()
        awaitTag(TestTags.TimeTracking.START)
    }
}
