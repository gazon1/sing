package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.CHILD
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.MILK
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.PARENT
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.TEST_CLOCK
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.TestTimeZone
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.seedChecklistItem
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.seedParent
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.seedSubtask
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.helpers.awaitCheckboxChecked
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickCheckbox
import com.singularity.todo.test.helpers.isCheckboxChecked
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop carrier for `TASK-ITEM-02` — see
 * `scripts/src/traceability/scenarios/tasks/item/TASK-ITEM-02.yaml`.
 *
 * The display-name annotation is the linkage; the id is its **first token**.
 * The assertions are on what the user reads — a checkbox that is really
 * checked — and on the state the production wiring writes: the task's own
 * completion flag is read back from the repository, because "ticking a
 * checklist item does not complete the task" is a claim about a write that did
 * not happen, not about a pixel.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskItemIndependenceScenarioTest {

    @Test
    @DisplayName("TASK-ITEM-02 a checklist item and a subtask do not change each other")
    fun a_checklist_item_and_a_subtask_do_not_change_each_other() = runDesktopAppTest(
        clock = TEST_CLOCK,
        overrides = module { single<TimeZoneProvider> { TestTimeZone } },
    ) { koin ->
        val parent = seedParent(koin)
        seedChecklistItem(koin, parent.id, MILK)
        val child = seedSubtask(koin, parent.id, CHILD)

        tapTab("Today")
        awaitTag(TestTags.taskItem(PARENT)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        // Ticking the checklist item touches the checklist item and nothing else.
        scrollToTag(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        clickCheckbox(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        awaitCheckboxChecked(TestTags.checklistItemCheckbox(id = MILK, title = MILK))

        assertFalse(
            isCheckboxChecked(TestTags.subtaskCheckbox(CHILD)),
            "the subtask must not be finished because a checklist item was ticked",
        )
        assertFalse(
            koin.get<TaskRepository>().get(parent.id)?.isCompleted == true,
            "the task must not be finished because a checklist item was ticked",
        )

        // Ticking the subtask finishes the subtask — and only the subtask. The
        // checklist keeps the state it had, and the task stays unfinished: a
        // task with all of its parts done is not a finished task.
        scrollToTag(TestTags.subtaskCheckbox(CHILD))
        clickCheckbox(TestTags.subtaskCheckbox(CHILD))
        awaitCheckboxChecked(TestTags.subtaskCheckbox(CHILD))

        assertTrue(
            isCheckboxChecked(TestTags.checklistItemCheckbox(id = MILK, title = MILK)),
            "the checklist item must keep its state because a subtask was ticked",
        )
        assertFalse(
            koin.get<TaskRepository>().get(parent.id)?.isCompleted == true,
            "the task must not be finished because its subtask was finished",
        )

        // The subtask itself went through the task lifecycle, which is a
        // different write from a checklist toggle.
        assertTrue(
            koin.get<TaskRepository>().get(child.id)?.isCompleted == true,
            "the subtask must be finished — it was ticked",
        )
    }
}
