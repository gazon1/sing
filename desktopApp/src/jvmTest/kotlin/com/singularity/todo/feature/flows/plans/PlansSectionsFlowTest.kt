package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop automation for the Plans scenarios about the days themselves —
 * `PLANS-01`, `PLANS-02`, `PLANS-05`, `PLANS-08` and `PLANS-10`.
 *
 * The clock is pinned, so "today" is the same day on both sides of the
 * assertion whatever day the suite runs. The day granularity is the default,
 * so these tests read the screen the way the user first meets it.
 *
 * The scenarios they carry are declared in
 * `scripts/src/traceability/scenarios/plans/`. The `@DisplayName` above is
 * the linkage: the id is the first token of the string.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansSectionsFlowTest {

    @Test
    @DisplayName("PLANS-01 days appear in chronological order, each with that day's tasks")
    fun days_in_chronological_order() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        // One robot for all three: a fresh robot starts its id counter again,
        // and two tasks with the same id are one task.
        tasks(koin)
            .given(due = TODAY, title = "Due today")
            .given(due = TODAY.plus(3, DateTimeUnit.DAY), title = "Due in three days")
            .given(due = TODAY.plus(6, DateTimeUnit.DAY), title = "Due in six days")

        tapTab("Plans")

        assertTagDisplayed(TestTags.plansSection("day:2026-09-16"))
        assertTagDisplayed(TestTags.plansSection("day:2026-09-19"))
        assertTagDisplayed(TestTags.plansSection("day:2026-09-22"))
        assertTagDisplayed(TestTags.taskItem("Due today"))
        assertTagDisplayed(TestTags.taskItem("Due in three days"))
        assertTagDisplayed(TestTags.taskItem("Due in six days"))

        // The day between the first and the second carries no task, so it has no
        // section of its own — an empty day is not a heading.
        awaitTagGone(TestTags.plansSection("day:2026-09-17"))

        // The sections are in date order: the header of the later day sits lower
        // on the screen than the header of the earlier one.
        val first = unclippedBoundsOfTag(TestTags.plansSection("day:2026-09-16")).top
        val last = unclippedBoundsOfTag(TestTags.plansSection("day:2026-09-22")).top
        kotlin.test.assertTrue(
            first < last,
            "the sections are not in chronological order: $first is not above $last",
        )
    }

    @Test
    @DisplayName("PLANS-02 a task appears in exactly one section at every granularity")
    fun a_task_appears_once_at_every_granularity() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).given(due = TODAY, title = "Only once")

        tapTab("Plans")
        assertTagDisplayed(TestTags.taskItem("Only once"))
        assertOneRow("Only once")

        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        awaitTag(TestTags.plansSection("week:2026-09-14"))
        assertOneRow("Only once")

        clickTag(TestTags.PLANS_GRANULARITY_MONTH)
        awaitTag(TestTags.plansSection("month:2026-9"))
        assertOneRow("Only once")
    }

    @Test
    @DisplayName("PLANS-05 overdue work leads the timeline and is not dated twice")
    fun overdue_leads_and_is_not_dated_twice() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).given(due = YESTERDAY, title = "Late task")
            .given(due = TODAY, title = "Due today")

        tapTab("Plans")

        assertTagDisplayed(TestTags.plansSection(TimelineGrouperIds.OVERDUE))
        assertTagDisplayed(TestTags.plansSection("day:2026-09-16"))

        val overdue = unclippedBoundsOfTag(TestTags.plansSection(TimelineGrouperIds.OVERDUE)).top
        val today = unclippedBoundsOfTag(TestTags.plansSection("day:2026-09-16")).top
        kotlin.test.assertTrue(
            overdue < today,
            "the overdue section must come before today's: $overdue is not above $today",
        )

        // The late task is in the overdue section and in no other one.
        assertOneRow("Late task")
    }

    @Test
    @DisplayName("PLANS-08 a section header names the period it covers")
    fun a_header_names_its_period() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).given(due = TODAY, title = "Due today")

        tapTab("Plans")
        // The day header names the day.
        awaitTag(TestTags.plansSection("day:2026-09-16"))
        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        // The week header names the range of that week.
        awaitTag(TestTags.plansSection("week:2026-09-14"))

        clickTag(TestTags.PLANS_GRANULARITY_MONTH)
        // The month header names the month.
        awaitTag(TestTags.plansSection("month:2026-9"))
        assertTagDisplayed(TestTags.taskItem("Due today"))
    }

    @Test
    @DisplayName("PLANS-10 an empty horizon says so and undated work stays out")
    fun an_empty_horizon_says_so() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Undated task")

        tapTab("Plans")

        assertTagDisplayed(TestTags.PLANS_EMPTY_STATE)
        // The undated task has no period to belong to, so it is not on the timeline.
        awaitTagGone(TestTags.taskItem("Undated task"))
    }

    @Test
    @DisplayName("PLANS-09 a task opens from its section and a new task inherits the section's date")
    fun a_task_opens_and_a_new_one_inherits_the_date() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).given(due = TODAY, title = "Due today")

        tapTab("Plans")

        // Tapping the row reaches that task's own screen.
        awaitTag(TestTags.taskItem("Due today")).performClick()
        assertTagDisplayed(TestTags.TASK_EDITOR_TITLE_INPUT)

        goBack()

        // The section's add control opens the editor with the section's date.
        clickTagScrolled(TestTags.plansSectionAdd("day:2026-09-16"))
        assertTagDisplayed(TestTags.TASK_EDITOR_TITLE_INPUT)
        // The due date is prefilled with the section's date — the day itself.
        assertTextDisplayed("2026-09-16")
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)
        val YESTERDAY: LocalDate = TODAY.minus(1, DateTimeUnit.DAY)
    }
}

/** The ids the grouper uses for the section that holds the late work. */
private object TimelineGrouperIds {
    const val OVERDUE = "overdue"
}

/**
 * The row of [title] appears exactly once.
 *
 * Counted, not looked at: a single stray row is invisible to the eye in a long
 * list, and a duplicated row is the failure this scenario is about.
 */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.assertOneRow(title: String) {
    val rows = countNodes(TestTags.taskItem(title))
    kotlin.test.assertEquals(1, rows, "the task \"$title\" is listed $rows times, not once")
}
