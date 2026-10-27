package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.TIMEOUT_MS
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.TITLE
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.openEditor
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.scrollToStateRow
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.seed
import com.singularity.todo.feature.flows.tasks.TaskStateScenarioSupport.today
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop automation for `TASK-STATE-03` — see
 * `scripts/src/traceability/scenarios/tasks/state/TASK-STATE-03.yaml`.
 *
 * The display-name annotation is the linkage: the id is its **first token**.
 * Completing a task must not reset the state it finished in, and the state
 * control of finished work must be inert.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskStateCompletedScenarioTest {

    @Test
    @DisplayName("TASK-STATE-03 a completed task keeps its workflow state and cannot change it")
    fun a_completed_task_keeps_its_workflow_state_and_cannot_change_it() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            val id = seed(
                koin,
                TITLE,
                due = today,
                workflowState = TaskWorkflowState.InProgress,
                completed = true,
            )
            openEditor(this, TITLE)

            // The state of the finished work is still readable.
            scrollToStateRow(this)
            assertTextDisplayed("In progress")

            // The control is inert: pressing it opens nothing.
            clickTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
            assertTagNotExists(TestTags.WORKFLOW_STATE_OPTION_OPEN)

            assertEquals(
                TaskWorkflowState.InProgress,
                withTimeout(TIMEOUT_MS) { koin.get<TaskRepository>().get(id)?.workflowState },
                "completing a task must not reset the state it finished in",
            )
        }
}
