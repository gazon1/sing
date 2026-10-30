package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.checklist.components.ChecklistItemRow
import com.singularity.todo.feature.checklist.components.ChecklistRowTags
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Section showing direct child tasks of a task.
 *
 * The section is always visible when rendered (even when [subtasks] is empty),
 * because the "add subtask" input row is how a subtask is created in the first
 * place. Callers that want to suppress the section for tasks that are themselves
 * subtasks should do so before calling this composable.
 *
 * @param onAddSubtask Called when the user submits the "add subtask" input.
 *                     The string is the new subtask title, already trimmed by
 *                     this composable. Empty input is not submitted.
 */
@Composable
fun SubtasksSection(
    subtasks: ImmutableList<Task>,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newTitleState = rememberTextFieldState()
    val completed = subtasks.count { it.isCompleted }
    val total = subtasks.size

    ExtraSectionCard(
        modifier = modifier.testTag(TestTags.SUBTASKS_SECTION),
        icon = { Text("\uD83D\uDCCB", style = MaterialTheme.typography.bodyMedium) },
        label = "Subtasks ($completed/$total)",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Add-subtask input row — always present so the user can add the first subtask.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        state = newTitleState,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.SUBTASK_ADD_INPUT),
                        placeholder = { Text("Add subtask...", style = MaterialTheme.typography.bodySmall) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        onKeyboardAction = {
                            val trimmed = newTitleState.text.toString().trim()
                            if (trimmed.isNotEmpty()) {
                                onAddSubtask(trimmed)
                                newTitleState.clearText()
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newTitleState.text.toString().trim()
                            if (trimmed.isNotEmpty()) {
                                onAddSubtask(trimmed)
                                newTitleState.clearText()
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .minimumInteractiveComponentSize()
                            .testTag(TestTags.SUBTASK_ADD_BUTTON),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add subtask",
                        )
                    }
                }
                // No cap: a task with fourteen subtasks shows fourteen of them.
                // The list used to show ten and say "+4 more", which is a
                // truncation the user cannot act on from the screen they are on.
                subtasks.forEach { task ->
                    ChecklistItemRow(
                        text = task.title,
                        checked = task.isCompleted,
                        // Ticking a subtask completes the subtask — the task
                        // lifecycle write, with its prerequisite guard — and
                        // nothing else. The parent is untouched.
                        onToggle = { onToggle(task) },
                        onDelete = { onDelete(task) },
                        // A subtask is a task and keeps its own screen, so its
                        // title opens it. A checklist item has no such screen.
                        onOpen = { onOpen(task) },
                        tags = ChecklistRowTags.forSubtask(task.title),
                    )
                }
            }
        },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SubtasksSectionEmptyPreview() = PreviewThemed(darkTheme = false) {
    SubtasksSection(
        subtasks = persistentListOf(),
        onToggle = { },
        onDelete = { },
        onOpen = { },
        onAddSubtask = { },
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SubtasksSectionWithItemsPreview() = PreviewThemed(darkTheme = false) {
    SubtasksSection(
        subtasks = persistentListOf(
            PreviewSamples.task(id = "t2", title = "Collect requirements", completed = true),
            PreviewSamples.task(id = "t3", title = "Draft the spec"),
        ),
        onToggle = { },
        onDelete = { },
        onOpen = { },
        onAddSubtask = { },
    )
}
