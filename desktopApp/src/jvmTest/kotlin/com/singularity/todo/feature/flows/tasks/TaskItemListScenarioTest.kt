package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.BREAD
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.CHILD
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.EGGS
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.MILK
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.PARENT
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.TEST_CLOCK
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.TestTimeZone
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.seedChecklistItem
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.seedParent
import com.singularity.todo.feature.flows.tasks.TaskItemScenarioSupport.seedSubtask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagEnabled
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitCheckboxChecked
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickCheckbox
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test

/**
 * Desktop carrier for `TASK-ITEM-01` — see
 * `scripts/src/traceability/scenarios/tasks/item/TASK-ITEM-01.yaml`.
 *
 * The display-name annotation is the linkage; the id is its **first token**,
 * and that strictness is the contract: the traceability scanner rejects an id
 * anywhere else rather than guessing. One scenario per file — the independence
 * scenario lives in `TaskItemIndependenceScenarioTest`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskItemListScenarioTest {

    @Test
    @DisplayName("TASK-ITEM-01 the task screen lists both kinds of item with checkboxes")
    fun the_task_screen_lists_both_kinds_of_item_with_checkboxes() = runDesktopAppTest(
        clock = TEST_CLOCK,
        overrides = module { single<TimeZoneProvider> { TestTimeZone } },
    ) { koin ->
        val parent = seedParent(koin)
        seedChecklistItem(koin, parent.id, MILK)
        seedChecklistItem(koin, parent.id, BREAD)
        seedSubtask(koin, parent.id, CHILD)

        tapTab("Today")
        awaitTag(TestTags.taskItem(PARENT)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

        // Both lists are on the task screen, each with a checkbox per row. The
        // checklist section renders even though the list was empty a moment ago
        // — it is where the add-row lives, so a section that hid itself would
        // leave no way to add the first item.
        scrollToTag(TestTags.TASK_CHECKLIST_SECTION)
        assertTagDisplayed(TestTags.TASK_CHECKLIST_SECTION)

        scrollToTag(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        assertTagDisplayed(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        assertTagDisplayed(TestTags.checklistItemCheckbox(id = BREAD, title = BREAD))

        // The counts are the finished items out of all of them, one number per
        // list.
        assertTextDisplayed("Checklist (0/2)")

        scrollToTag(TestTags.subtaskCheckbox(CHILD))
        assertTagDisplayed(TestTags.SUBTASKS_SECTION)
        assertTagDisplayed(TestTags.subtaskCheckbox(CHILD))
        assertTextDisplayed("Subtasks (0/1)")

        // Ticking a checklist item ticks that item, and the count follows.
        scrollToTag(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        clickCheckbox(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        awaitCheckboxChecked(TestTags.checklistItemCheckbox(id = MILK, title = MILK))
        awaitText("Checklist (1/2)")

        // An item is added from the screen itself: the add-row is inside the
        // section, and nothing opens over the task while it happens.
        scrollToTag(TestTags.CHECKLIST_INLINE_ADD_INPUT)
        typeIntoTag(TestTags.CHECKLIST_INLINE_ADD_INPUT, EGGS)
        assertTagEnabled(TestTags.CHECKLIST_INLINE_ADD_BUTTON)
        clickTag(TestTags.CHECKLIST_INLINE_ADD_BUTTON)
        awaitTag(TestTags.checklistItem(id = EGGS, title = EGGS))
        scrollToTag(TestTags.checklistItem(id = EGGS, title = EGGS))
        assertTagDisplayed(TestTags.checklistItem(id = EGGS, title = EGGS))

        // The checklist sheet is still a thing this app has, and it is not what
        // the add-row opened.
        assertTagNotExists(TestTags.CHECKLIST_SHEET)

        awaitText("Checklist (1/3)")
    }
}
