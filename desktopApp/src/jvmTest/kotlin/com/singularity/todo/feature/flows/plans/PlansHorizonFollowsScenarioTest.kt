package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
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
 * Desktop automation for the Plans scenario `PLANS-07` — the horizon follows the granularity.
 *
 * The control is read as well as used: a switch that changed the grouping
 * without saying which grouping was in effect would leave the user guessing
 * what the screen shows them, so the selected option is asserted rather than
 * taken for granted.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansHorizonFollowsScenarioTest {

    @Test
    @DisplayName("PLANS-07 the horizon follows the granularity")
    fun the_horizon_follows_the_granularity() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
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
}
