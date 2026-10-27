package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.YESTERDAY
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-05` — overdue work leads the timeline and is not dated twice.
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
class PlansOverdueLeadsScenarioTest {

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
}
