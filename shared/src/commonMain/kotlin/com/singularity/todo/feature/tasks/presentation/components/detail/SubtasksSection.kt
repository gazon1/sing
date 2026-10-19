package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.Task

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
    subtasks: List<Task>,
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
                            .testTag(TestTags.SUBTASK_ADD_BUTTON),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add subtask",
                        )
                    }
                }

                subtasks.take(10).forEach { task ->
                    SubtaskRow(
                        task = task,
                        onToggle = { onToggle(task) },
                        onDelete = { onDelete(task) },
                        onOpen = { onOpen(task) },
                    )
                }
                if (subtasks.size > 10) {
                    Text(
                        text = "+${subtasks.size - 10} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun SubtaskRow(
    task: Task,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val title = task.title

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 2.dp)
            .testTag(TestTags.subtaskItem(title)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (task.isCompleted) "✓ ${task.title}" else task.title,
            style = MaterialTheme.typography.bodySmall,
            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
            color = if (task.isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen() },
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .height(24.dp)
                .testTag(TestTags.subtaskDelete(title)),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete subtask",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SubtasksSectionEmptyPreview() = PreviewThemed(darkTheme = false) {
    SubtasksSection(
        subtasks = emptyList(),
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
        subtasks = listOf(
            PreviewSamples.task(id = "t2", title = "Collect requirements", completed = true),
            PreviewSamples.task(id = "t3", title = "Draft the spec"),
        ),
        onToggle = { },
        onDelete = { },
        onOpen = { },
        onAddSubtask = { },
    )
}
