package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-10` — an empty horizon says so and undated work stays out.
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
class PlansEmptyHorizonScenarioTest {

    @Test
    @DisplayName("PLANS-10 an empty horizon says so and undated work stays out")
    fun an_empty_horizon_says_so() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).givenUndated(title = "Undated task")

        tapTab("Plans")

        assertTagDisplayed(TestTags.PLANS_EMPTY_STATE)
        // The undated task has no period to belong to, so it is not on the timeline.
        awaitTagGone(TestTags.taskItem("Undated task"))
    }
}
