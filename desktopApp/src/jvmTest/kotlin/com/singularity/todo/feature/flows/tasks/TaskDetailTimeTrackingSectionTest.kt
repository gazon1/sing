package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.time.Instant

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
 * live in code. Both graphs now render `TaskDetailContent`, so this test fails if
 * they drift apart again.
 *
 * A second task-detail screen existed from `45a0831e` as a clock-suppression
 * carrier with no call site, so the one-screen invariant from #187 was already
 * violated and `find-unwired-surfaces.py` reported it. It was deleted on
 * 2026-10-06: 633 unreachable lines whose only remaining mention was this note.
 * The route under test is the shared content screen, and nothing composes a
 * second screen now — so this test guards the same thing it was written to guard.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TaskDetailTimeTrackingSectionTest {

    @Test
    @DisplayName("the desktop task detail offers a time-tracking control")
    fun the_desktop_task_detail_offers_a_time_tracking_control() = runDesktopAppTest(
        clock = TEST_CLOCK,
        overrides = module { single<TimeZoneProvider> { TestTimeZone } },
    ) { koin ->
        // The task's due date and the agenda's "today" must come from the same
        // pair. This used to be `todayInSystemZone()`, i.e. the host's real date,
        // which made the flow pass on the day it was written and drift across a
        // midnight boundary — the same defect `CalendarFlowTest` had, and the
        // reason `todayAt` now demands a clock and a zone rather than defaulting
        // to either.
        tasks(koin).given(due = todayAt(TEST_CLOCK, TEST_ZONE), title = "Track me")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Track me")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        awaitTag(TestTags.TimeTracking.START).assertIsDisplayed()

        // The click that used to live here has moved to `TaskTimeCarrierTest`, and
        // its removal is the point.
        //
        // This test asserted "the click is dispatched without throwing, and the
        // chip is still the one thing under the finger afterwards". That assertion
        // passed only because the click did *nothing* — #212 was a type collision
        // that dropped every timer intent into a silent `else { }`, so the chip
        // could not change whatever happened. When the collision was fixed the
        // timer started, the chip became Stop, and this test failed on correct
        // behaviour.
        //
        // A guard that pins the defect in place is worse than no guard: it turns
        // the fix into a regression. What this test is for is narrower and still
        // worth having — the desktop task detail *renders* time tracking, which
        // is the #187 regression. Whether the control then works is
        // `TASK-TIME-01`'s question, and it now has a carrier to ask it.
    }

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test can
         * roll the date across a midnight boundary and empty the Today tab.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
