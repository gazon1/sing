package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
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
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop automation for `TASK-STATE-02` — see
 * `scripts/src/traceability/scenarios/tasks/state/TASK-STATE-02.yaml`.
 *
 * The display-name annotation is the linkage: the id is its **first token**.
 * The state is read back from the database, because "it survives leaving the
 * screen" is a claim about storage, not about a pixel.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskStateSurvivesScenarioTest {

    @Test
    @DisplayName("TASK-STATE-02 a workflow state survives leaving and reopening the task")
    fun a_workflow_state_survives_leaving_and_reopening_the_task() =
        runDesktopAppTest(clock = CLOCK) { koin ->
            val id = seed(koin, TITLE, due = today)
            openEditor(this, TITLE)

            scrollToStateRow(this)
            clickTag(TestTags.TASK_EDITOR_WORKFLOW_STATE_ROW)
            awaitTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)
            clickTag(TestTags.WORKFLOW_STATE_OPTION_IN_PROGRESS)
            awaitText("In progress")

            // Leave the screen, then come back to the same task. The task
            // editor's back arrow carries a content description, not a tag.
            onAllNodesWithContentDescription("Назад").onFirst().performClick()
            awaitTag(TestTags.taskItem(TITLE)).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            scrollToStateRow(this)
            assertTextDisplayed("In progress")
            assertEquals(
                TaskWorkflowState.InProgress,
                withTimeout(TIMEOUT_MS) { koin.get<TaskRepository>().get(id)?.workflowState },
                "the state must be on the task, not in the screen that was open",
            )
        }
}
