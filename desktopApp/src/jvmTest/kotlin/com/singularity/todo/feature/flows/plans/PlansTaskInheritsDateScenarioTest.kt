package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-09` — a task opens from its section and a new task inherits the
 * section's date.
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
class PlansTaskInheritsDateScenarioTest {

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
}
