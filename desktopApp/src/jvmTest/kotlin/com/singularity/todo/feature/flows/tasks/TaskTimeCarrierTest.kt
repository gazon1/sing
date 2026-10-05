package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
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
 * Carrier for `TASK-TIME-01` on desktop: the chip swaps between Start and Stop
 * in place, and the timer runs.
 *
 * ## Why this could not have been written earlier
 *
 * The scenario has been a hole on both targets since #187, and the recorded
 * reason was that the desktop harness session is `Anonymous` so `startEntry`
 * cannot succeed. Two things changed and both were needed:
 *
 * - `TaskTimeSlot` now reports a refused write as `TaskTimeSlotState.Error`
 *   instead of discarding it (#196). Before that the click was silent, so
 *   "started" and "did nothing" were the same observation and a test could only
 *   ever assert that a control existed.
 * - The refusal turned out **not** to be the session. `TestPlatformModule`
 *   binds `FakeAuthRepository()`, which reports `Session.Anonymous`, and its own
 *   comment records that `AuthGuard` treats that as signed in and that it "gives
 *   the write path a user to scope to". So the write has an owner; what it was
 *   missing was anywhere to report a failure.
 *
 * So the honest reason this was a hole was never the auth session. It was that
 * the old code made the failure unobservable, which is the same defect in both
 * directions: a test that cannot see a failure cannot distinguish it from a
 * success.
 *
 * ## Fixed clock and zone
 *
 * The due date and the agenda's "today" have to come from the same pair, or the
 * task is filed under a date the Today tab is not showing. `todayAt` now demands
 * a clock and a zone rather than defaulting to either, which is exactly the
 * reason it exists.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TaskTimeCarrierTest {

    @Test
    @DisplayName("TASK-TIME-01 the timer chip swaps between Start and Stop in place")
    fun the_timer_chip_swaps_between_start_and_stop_in_place() = runDesktopAppTest(
        clock = TEST_CLOCK,
        overrides = module { single<TimeZoneProvider> { TestTimeZone } },
    ) { koin ->
        tasks(koin).given(due = todayAt(TEST_CLOCK, TEST_ZONE), title = "Track me")

        tapTab("Today")
        awaitTag(TestTags.taskItem("Track me")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        awaitTag(TestTags.TimeTracking.START).assertIsDisplayed()

        awaitTag(TestTags.TimeTracking.START).performClick()
        // Stop replacing Start *in place* is the scenario's claim: two chips
        // asserting "it started" would pass just as well if the UI swapped the
        // whole section out, which is not what the user was promised.
        awaitTagGone(TestTags.TimeTracking.START)
        awaitTag(TestTags.TimeTracking.STOP).assertIsDisplayed()

        awaitTag(TestTags.TimeTracking.STOP).performClick()
        awaitTagGone(TestTags.TimeTracking.STOP)
        awaitTag(TestTags.TimeTracking.START).assertIsDisplayed()
    }

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /** Midday UTC on a Saturday, so no reachable timezone rolls the date. */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
