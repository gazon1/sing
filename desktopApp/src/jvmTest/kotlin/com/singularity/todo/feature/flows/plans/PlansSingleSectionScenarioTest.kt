package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-02` — a task appears in exactly one section at every granularity.
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
class PlansSingleSectionScenarioTest {

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
}
