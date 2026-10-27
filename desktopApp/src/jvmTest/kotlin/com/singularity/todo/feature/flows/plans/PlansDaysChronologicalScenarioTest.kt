package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-01` — days appear in chronological order, each with that day's
 * tasks.
 *
 * The clock is pinned, so "today" is the same day on both sides of the
 * assertion whatever day the suite runs. The day granularity is the default,
 * so these tests read the screen the way the user first meets it.
 *
 * The scenario it carries is declared in
 * `scripts/src/traceability/scenarios/plans/`. The `@DisplayName` above is
 * the linkage: the id is the first token of the string.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansDaysChronologicalScenarioTest {

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
}
