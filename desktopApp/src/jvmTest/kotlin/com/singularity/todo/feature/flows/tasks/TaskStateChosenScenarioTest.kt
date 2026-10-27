package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.TITLE
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.openEditor
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.scrollToStateRow
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.seed
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.storedState
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.today
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop automation for `TASK-STATE-01` — see
 * `scripts/src/traceability/scenarios/tasks/state/TASK-STATE-01.yaml`.
 *
 * The display-name annotation is the linkage: the id is its **first token**.
 * One scenario per file — see `TaskStateScenarioSupport` for the fixture the
 * three `TASK-STATE` scenarios share.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskStateChosenScenarioTest {

    @Test
    @DisplayName("TASK-STATE-01 a workflow state is chosen on the task screen")
    fun a_workflow_state_is_chosen_on_the_task_screen() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            seed(koin, TITLE, due = today)
            openEditor(this, TITLE)

            // The control states the current state before it is opened.
            scrollToStateRow(this)
            assertTextDisplayed("No state")

            clickTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
            awaitTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)
            clickTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)

            // The screen shows the chosen state, and the task is still open.
            awaitText("In progress")
            assertEquals(TaskWorkflowState.InProgress, storedState(koin, TITLE))
        }
}
