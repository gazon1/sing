package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop automation for the Plans scenarios about the granularity —
 * `PLANS-03`, `PLANS-04` and `PLANS-07`.
 *
 * The control is read as well as used: a switch that changed the grouping
 * without saying which grouping was in effect would leave the user guessing
 * what the screen shows them, so the selected option is asserted rather than
 * taken for granted.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansGranularityFlowTest {

    @Test
    @DisplayName("PLANS-03 the granularity control switches the grouping and names the selection")
    fun the_control_switches_and_names() = runDesktopAppTest(clock = CLOCK) { koin ->
        // One robot for both: a fresh robot starts its id counter again, and
        // two tasks with the same id are one task.
        tasks(koin)
            .given(due = TODAY, title = "Due today")
            .given(due = TODAY.plus(70, DateTimeUnit.DAY), title = "Due in two months")

        tapTab("Plans")

        // The control names the granularity that is in effect.
        onNodeWithTag(TestTags.PLANS_GRANULARITY_DAY).assertIsSelected()
        awaitTag(TestTags.plansSection("day:2026-09-16"))

        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        onNodeWithTag(TestTags.PLANS_GRANULARITY_WEEK).assertIsSelected()
        // A week header is a range running Monday to Sunday.
        awaitTag(TestTags.plansSection("week:2026-09-14"))

        clickTag(TestTags.PLANS_GRANULARITY_MONTH)
        onNodeWithTag(TestTags.PLANS_GRANULARITY_MONTH).assertIsSelected()
        // A month header is the calendar month.
        awaitTag(TestTags.plansSection("month:2026-9"))
        assertTagDisplayed(TestTags.taskItem("Due in two months"))
    }

    @Test
    @DisplayName("PLANS-04 the chosen granularity is restored the next time Plans is opened")
    fun the_choice_survives_leaving() = runDesktopAppTest(clock = CLOCK) { koin ->
        tasks(koin).given(due = TODAY, title = "Due today")

        tapTab("Plans")
        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        onNodeWithTag(TestTags.PLANS_GRANULARITY_WEEK).assertIsSelected()

        // Leave for another tab and come back.
        tapTab("Inbox")
        tapTab("Plans")

        onNodeWithTag(TestTags.PLANS_GRANULARITY_WEEK).assertIsSelected()
        awaitTag(TestTags.plansSection("week:2026-09-14"))
    }

    @Test
    @DisplayName("PLANS-07 the horizon follows the granularity")
    fun the_horizon_follows_the_granularity() = runDesktopAppTest(clock = CLOCK) { koin ->
        // One robot for both: a fresh robot starts its id counter again, and
        // two tasks with the same id are one task.
        tasks(koin)
            .given(due = TODAY.plus(1, DateTimeUnit.DAY), title = "Due tomorrow")
            .given(due = TODAY.plus(45, DateTimeUnit.DAY), title = "Due in forty-five days")

        tapTab("Plans")
        assertTagDisplayed(TestTags.taskItem("Due tomorrow"))
        // Thirty days of days end before the task that is due in forty-five.
        awaitTagGone(TestTags.taskItem("Due in forty-five days"))

        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        assertTagDisplayed(TestTags.taskItem("Due tomorrow"))
        // Twelve weeks reach forty-five days.
        assertTagDisplayed(TestTags.taskItem("Due in forty-five days"))

        clickTag(TestTags.PLANS_GRANULARITY_MONTH)
        assertTagDisplayed(TestTags.taskItem("Due tomorrow"))
        // Twelve months of months reach the task too.
        assertTagDisplayed(TestTags.taskItem("Due in forty-five days"))
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)
    }
}
