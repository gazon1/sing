package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.VIEW_NAME
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.createInProgressView
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.moveToInProgress
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.openSavedView
import com.singularity.todo.feature.flows.agenda.AgendaStateScenarioSupport.seed
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for `AGENDA-STATE-02` — see
 * `scripts/src/traceability/scenarios/agenda/state/AGENDA-STATE-02.yaml`.
 *
 * The `@DisplayName` is the linkage: the id is its **first token**. The view
 * stores a selector, so changing the task and reopening the same, unedited
 * view is the claim this scenario makes.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaStateFollowScenarioTest {

    @Test
    @DisplayName("AGENDA-STATE-02 a saved view follows a task after its state changes")
    fun a_saved_view_follows_a_task_after_its_state_changes() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, "Idle task", TaskWorkflowState.Open)
            seed(koin, "Other task", TaskWorkflowState.Open)
            createInProgressView(koin)

            tapTab("Inbox")
            openSavedView(this, VIEW_NAME)
            assertTagNotExists(TestTags.taskItem("Idle task"))

            // Leave the results screen, move the task through its own write
            // path, then open the same card again — the view is not edited.
            awaitTag(TestTags.TOP_BAR_BACK_BUTTON).performClick()
            awaitTag(TestTags.savedAgendaCard(VIEW_NAME))
            moveToInProgress(koin, "Idle task")

            awaitTag(TestTags.savedAgendaCard(VIEW_NAME)).performClick()
            assertTagDisplayed(TestTags.agendaSection("In progress"))
            assertTagDisplayed(TestTags.taskItem("Idle task"))
            assertTagNotExists(TestTags.taskItem("Other task"))
        }
}
