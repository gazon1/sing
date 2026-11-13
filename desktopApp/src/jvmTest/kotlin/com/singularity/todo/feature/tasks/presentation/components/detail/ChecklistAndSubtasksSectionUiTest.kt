@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistMove
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checklist and subtask sections of the task detail.
 *
 * The long-list rule is the same in both sections: five rows at most until the
 * reader asks for the rest. The tests pin the boundaries the rule is easy to get
 * wrong at — five items show no control, six show it, and expanding shows every
 * row — and the tap contract that keeps the two lists apart: a subtask's title
 * opens it, a checklist item's title does nothing, and the checkbox is the only
 * control that toggles either.
 */
@Tag("fast")
class ChecklistAndSubtasksSectionUiTest {
    private fun checklistItem(
        id: String,
        title: String,
        completed: Boolean = false,
    ) = ChecklistItem(
        id = ChecklistItemId.fromString(id),
        taskId = "t1",
        title = title,
        isCompleted = completed,
    )

    private fun checklist(count: Int) =
        (1..count)
            .map { checklistItem("c$it", "Item $it") }
            .toImmutableList()

    private fun subtasks(count: Int) =
        (1..count)
            .map { testTask(id = TaskId("s$it"), title = "Child $it") }
            .toImmutableList()

    @Test
    fun `an empty checklist keeps the add-row and says it is empty`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailChecklistSection(
                    checklist = persistentListOf(),
                    onToggle = {},
                    onDelete = {},
                    onAddItem = {},
                )
            }

            assertTagDisplayed(TestTags.CHECKLIST_EMPTY_HINT)
            assertTagDisplayed(TestTags.CHECKLIST_INLINE_ADD_INPUT)
            assertTagNotExists(TestTags.CHECKLIST_SHOW_ALL)
        }

    @Test
    fun `five checklist items need no expand control`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailChecklistSection(
                    checklist = checklist(5),
                    onToggle = {},
                    onDelete = {},
                    onAddItem = {},
                )
            }

            assertTagDisplayed(TestTags.checklistItem("c5", "Item 5"))
            assertTagNotExists(TestTags.CHECKLIST_SHOW_ALL)
        }

    @Test
    fun `six checklist items show five rows and expand to all of them`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailChecklistSection(
                    checklist = checklist(6),
                    onToggle = {},
                    onDelete = {},
                    onAddItem = {},
                )
            }

            assertTagDisplayed(TestTags.checklistItem("c5", "Item 5"))
            assertTagNotExists(TestTags.checklistItem("c6", "Item 6"))
            assertTagDisplayed(TestTags.CHECKLIST_SHOW_ALL)
            assertTextDisplayed("Показать все 6")

            clickTag(TestTags.CHECKLIST_SHOW_ALL)

            assertTagDisplayed(TestTags.checklistItem("c6", "Item 6"))
            assertTagNotExists(TestTags.CHECKLIST_SHOW_ALL)
        }

    @Test
    fun `the checklist label reports the completed count`() =
        runIsolatedComposeTest {
            val items =
                listOf(
                    checklistItem("c1", "Item 1", completed = true),
                    checklistItem("c2", "Item 2", completed = true),
                    checklistItem("c3", "Item 3"),
                    checklistItem("c4", "Item 4"),
                ).toImmutableList()
            setContent {
                TaskDetailChecklistSection(
                    checklist = items,
                    onToggle = {},
                    onDelete = {},
                    onAddItem = {},
                )
            }

            assertTextDisplayed("Checklist (2/4)")
        }

    @Test
    fun `a checklist item's title is inert and its checkbox is the toggle`() =
        runIsolatedComposeTest {
            val toggled = mutableListOf<ChecklistItem>()
            setContent {
                TaskDetailChecklistSection(
                    checklist = checklist(1),
                    onToggle = { toggled += it },
                    onDelete = {},
                    onAddItem = {},
                )
            }

            clickTag(TestTags.checklistItem("c1", "Item 1"))
            assertTrue(toggled.isEmpty(), "a tap on the title must not complete the item")

            clickTag(TestTags.checklistItemCheckbox("c1", "Item 1"))
            assertEquals(listOf("Item 1"), toggled.map { it.title })
        }

    @Test
    fun `six subtasks show five rows and expand to all of them`() =
        runIsolatedComposeTest {
            setContent {
                SubtasksSection(
                    subtasks = subtasks(6),
                    onToggle = {},
                    onDelete = {},
                    onOpen = {},
                    onAddSubtask = {},
                )
            }

            assertTagDisplayed(TestTags.subtaskItem("Child 5"))
            assertTagNotExists(TestTags.subtaskItem("Child 6"))
            assertTagDisplayed(TestTags.SUBTASKS_SHOW_ALL)
            assertTextDisplayed("Показать все 6")

            clickTag(TestTags.SUBTASKS_SHOW_ALL)

            assertTagDisplayed(TestTags.subtaskItem("Child 6"))
            assertTagNotExists(TestTags.SUBTASKS_SHOW_ALL)
        }

    @Test
    fun `a subtask's title opens it and its checkbox completes it`() =
        runIsolatedComposeTest {
            val opened = mutableListOf<Task>()
            val toggled = mutableListOf<Task>()
            setContent {
                SubtasksSection(
                    subtasks = subtasks(1),
                    onToggle = { toggled += it },
                    onDelete = {},
                    onOpen = { opened += it },
                    onAddSubtask = {},
                )
            }

            clickTag(TestTags.subtaskItem("Child 1"))
            assertEquals(listOf("Child 1"), opened.map { it.title })
            assertTrue(toggled.isEmpty(), "a tap on the title opens the subtask; it does not complete it")

            clickTag(TestTags.subtaskCheckbox("Child 1"))
            assertEquals(listOf("Child 1"), toggled.map { it.title })
        }

    @Test
    fun `reorder controls step the item and are disabled at the ends`() =
        runIsolatedComposeTest {
            val moves = mutableListOf<Pair<String, ChecklistMove>>()
            setContent {
                TaskDetailChecklistSection(
                    checklist = checklist(3),
                    onToggle = {},
                    onDelete = {},
                    onAddItem = {},
                    onMove = { id, direction -> moves += id.value to direction },
                )
            }

            onNodeWithTag(TestTags.checklistItemMoveUp("c1", "Item 1")).assertIsNotEnabled()
            onNodeWithTag(TestTags.checklistItemMoveDown("c1", "Item 1")).assertIsEnabled()
            onNodeWithTag(TestTags.checklistItemMoveUp("c3", "Item 3")).assertIsEnabled()
            onNodeWithTag(TestTags.checklistItemMoveDown("c3", "Item 3")).assertIsNotEnabled()

            clickTag(TestTags.checklistItemMoveDown("c1", "Item 1"))
            assertEquals(listOf("c1" to ChecklistMove.Down), moves)
        }

    @Test
    fun `a single checklist item has no reorder controls`() =
        runIsolatedComposeTest {
            setContent {
                TaskDetailChecklistSection(
                    checklist = checklist(1),
                    onToggle = {},
                    onDelete = {},
                    onAddItem = {},
                    onMove = { _, _ -> },
                )
            }

            assertTagNotExists(TestTags.checklistItemMoveUp("c1", "Item 1"))
            assertTagNotExists(TestTags.checklistItemMoveDown("c1", "Item 1"))
        }

    @Test
    fun `subtasks show no reorder controls`() =
        runIsolatedComposeTest {
            setContent {
                SubtasksSection(
                    subtasks = subtasks(3),
                    onToggle = {},
                    onDelete = {},
                    onOpen = {},
                    onAddSubtask = {},
                )
            }

            assertTagNotExists(TestTags.checklistItemMoveUp("s1", "Child 1"))
            assertTagNotExists(TestTags.checklistItemMoveDown("s1", "Child 1"))
        }
}
