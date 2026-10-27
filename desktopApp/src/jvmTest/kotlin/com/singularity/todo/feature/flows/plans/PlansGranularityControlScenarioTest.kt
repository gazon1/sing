package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagSelected
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-03` — the granularity control switches the grouping and names the
 * selection.
 *
 * The control is read as well as used: a switch that changed the grouping
 * without saying which grouping was in effect would leave the user guessing
 * what the screen shows them, so the selected option is asserted rather than
 * taken for granted.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansGranularityControlScenarioTest {

    @Test
    @DisplayName("PLANS-03 the granularity control switches the grouping and names the selection")
    fun the_control_switches_and_names() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        // One robot for both: a fresh robot starts its id counter again, and
        // two tasks with the same id are one task.
        tasks(koin)
            .given(due = TODAY, title = "Due today")
            .given(due = TODAY.plus(70, DateTimeUnit.DAY), title = "Due in two months")

        tapTab("Plans")

        // The control names the granularity that is in effect.
        assertTagSelected(TestTags.PLANS_GRANULARITY_DAY)
        awaitTag(TestTags.plansSection("day:2026-09-16"))

        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        assertTagSelected(TestTags.PLANS_GRANULARITY_WEEK)
        // A week header is a range running Monday to Sunday.
        awaitTag(TestTags.plansSection("week:2026-09-14"))

        clickTag(TestTags.PLANS_GRANULARITY_MONTH)
        assertTagSelected(TestTags.PLANS_GRANULARITY_MONTH)
        // A month header is the calendar month.
        awaitTag(TestTags.plansSection("month:2026-9"))
        assertTagDisplayed(TestTags.taskItem("Due in two months"))
    }
}
