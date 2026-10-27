package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.plans.PlansScenarioSupport.TODAY
import com.singularity.todo.test.helpers.assertTagSelected
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the Plans scenario `PLANS-04` — the chosen granularity is restored the next time Plans is
 * opened.
 *
 * The control is read as well as used: a switch that changed the grouping
 * without saying which grouping was in effect would leave the user guessing
 * what the screen shows them, so the selected option is asserted rather than
 * taken for granted.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansGranularityRestoredScenarioTest {

    @Test
    @DisplayName("PLANS-04 the chosen granularity is restored the next time Plans is opened")
    fun the_choice_survives_leaving() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        tasks(koin).given(due = TODAY, title = "Due today")

        tapTab("Plans")
        clickTag(TestTags.PLANS_GRANULARITY_WEEK)
        assertTagSelected(TestTags.PLANS_GRANULARITY_WEEK)

        // Leave for another tab and come back.
        tapTab("Inbox")
        tapTab("Plans")

        assertTagSelected(TestTags.PLANS_GRANULARITY_WEEK)
        awaitTag(TestTags.plansSection("week:2026-09-14"))
    }
}
