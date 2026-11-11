package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.checklist.components.ChecklistItemRow
import com.singularity.todo.feature.checklist.components.ChecklistRowTags
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowContent
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickCheckbox
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The accessible names of the completion controls (issue #2753).
 *
 * A completion control used to be a nameless dot on a row: the list checkbox
 * carried no description at all, the card's toggle said "Toggle complete" for
 * every task on the screen, and the checklist row's box said nothing. A screen
 * reader user heard a checkbox and had to guess what it belonged to.
 *
 * The name is the kind and the title — "Task: Buy milk", "Subtask: Buy milk",
 * "Item: Buy milk" — and the completed state stays a state, so the name does
 * not have to change when the control changes what it completed.
 *
 * The assertions read the *value* of the description rather than only its
 * presence: the a11y scan accepts a test tag as a label, so a control that
 * only has a tag would pass it while staying nameless for a screen reader.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskCompletionSemanticsTest {
    @Test
    fun `the list checkbox names the task it completes`() =
        runIsolatedComposeTest {
            var toggles = 0
            setContent {
                SingularityTheme(themeMode = ThemeMode.Light) {
                    TaskRowContent(task = taskUi(), onToggleCompleted = { toggles++ })
                }
            }

            val config = semanticsOf(TestTags.taskCheckbox(TITLE))
            assertEquals(
                listOf("Task: Buy milk"),
                config.getOrNull(SemanticsProperties.ContentDescription),
                "the checkbox names the task it completes",
            )
            assertEquals(Role.Checkbox, config.getOrNull(SemanticsProperties.Role))
            assertEquals("Not done", config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(ToggleableState.Off, config.getOrNull(SemanticsProperties.ToggleableState))

            clickCheckbox(TestTags.taskCheckbox(TITLE))
            assertEquals(1, toggles, "the checkbox completes the task")
        }

    @Test
    fun `the card toggle names the task and does not open the card`() =
        runIsolatedComposeTest {
            var toggles = 0
            var opens = 0
            setContent {
                SingularityTheme(themeMode = ThemeMode.Light) {
                    TaskCard(
                        task = task(),
                        onClick = { opens++ },
                        actions = TaskCardActions(onToggle = { toggles++ }),
                    )
                }
            }

            val config = semanticsOf(TestTags.taskCheckbox(TITLE))
            assertEquals(
                listOf("Task: Buy milk"),
                config.getOrNull(SemanticsProperties.ContentDescription),
                "the toggle names the task it completes",
            )
            assertEquals(
                Role.Checkbox,
                config.getOrNull(SemanticsProperties.Role),
                "the control completes a task, so it is a checkbox and not a button",
            )
            assertEquals("Not done", config.getOrNull(SemanticsProperties.StateDescription))

            // The card is a clickable row and the toggle sits inside it. The two
            // are separate nodes — a merged one would swallow the toggle and
            // leave a screen reader with a row and no way to complete the task.
            clickCheckbox(TestTags.taskCheckbox(TITLE))
            assertEquals(1, toggles, "the toggle completes the task")
            assertEquals(0, opens, "completing the task does not open the card")
        }

    @Test
    fun `the checklist row names a subtask and an item apart`() =
        runIsolatedComposeTest {
            var toggles = 0
            var opens = 0
            setContent {
                SingularityTheme(themeMode = ThemeMode.Light) {
                    Column {
                        ChecklistItemRow(
                            text = TITLE,
                            checked = false,
                            onToggle = { toggles++ },
                            onOpen = { opens++ },
                            tags = ChecklistRowTags.forSubtask(TITLE),
                        )
                        ChecklistItemRow(
                            text = TITLE,
                            checked = false,
                            onToggle = { toggles++ },
                            tags = ChecklistRowTags.forChecklistItem(id = ITEM_ID, title = TITLE),
                        )
                    }
                }
            }

            assertEquals(
                listOf("Subtask: Buy milk"),
                semanticsOf(TestTags.subtaskCheckbox(TITLE)).getOrNull(SemanticsProperties.ContentDescription),
                "a subtask is named as a subtask",
            )
            assertEquals(
                listOf("Item: Buy milk"),
                semanticsOf(TestTags.checklistItemCheckbox(id = ITEM_ID, title = TITLE))
                    .getOrNull(SemanticsProperties.ContentDescription),
                "a checklist item is named as an item",
            )

            // The title of a subtask opens the subtask; the box completes it.
            clickCheckbox(TestTags.subtaskCheckbox(TITLE))
            assertEquals(1, toggles, "the checkbox completes the subtask")
            assertEquals(0, opens, "completing the subtask does not open it")
        }

    /** The semantics of the node with [tag] — the node the user reaches, not its parent. */
    private fun DesktopComposeUiTest.semanticsOf(tag: String) = awaitTag(tag).fetchSemanticsNode().config

    private fun taskUi() =
        TaskUi(
            id = TaskId("t-completion"),
            title = TITLE,
            project = null,
            dueLabel = null,
        )

    private fun task() =
        Task(
            id = TaskId("t-completion"),
            title = TITLE,
            createdAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_760_000_000_000),
            userId = UserId("u1"),
        )

    private companion object {
        const val TITLE = "Buy milk"
        const val ITEM_ID = "i-1"
    }
}
