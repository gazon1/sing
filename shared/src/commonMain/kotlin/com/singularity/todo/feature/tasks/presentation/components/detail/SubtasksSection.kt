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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.sectionTitle
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.checklist.components.ChecklistItemRow
import com.singularity.todo.feature.checklist.components.ChecklistRowTags
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.preview.PreviewSamples
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Section showing direct child tasks of a task.
 *
 * The section is always visible when rendered (even when [subtasks] is empty),
 * because its header is the way to write into it — a section that hid itself
 * when the list was empty would take the way in with it. What an empty list
 * must not do is report a count over nothing: the header says "Subtasks" and
 * nothing else until the section is opened.
 *
 * Callers that want to suppress the section for tasks that are themselves
 * subtasks should do so before calling this composable.
 *
 * @param onAddSubtask Called when the user submits the "add subtask" input.
 *                     The string is the new subtask title, already trimmed by
 *                     this composable. Empty input is not submitted.
 * @param expanded Whether the section is open. The header is the toggle.
 * @param onToggleExpanded Called when the header is tapped. `null` means the
 *                     section cannot be collapsed and its content is always
 *                     drawn.
 */
@Composable
fun SubtasksSection(
    subtasks: ImmutableList<Task>,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
) {
    val completed = subtasks.count { it.isCompleted }
    val total = subtasks.size
    // Two different expansions, kept apart. The section's own open state belongs to
    // the screen, which remembers it; the long list's "show all" belongs to the
    // list, and the screen has no reason to remember it.
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visibleSubtasks = if (!showAll && subtasks.size > 5) subtasks.take(5) else subtasks

    ExtraSectionCard(
        modifier = modifier,
        headerTestTag = TestTags.SUBTASKS_SECTION,
        icon = Icons.Filled.Checklist,
        label = sectionTitle("Subtasks", total, completed),
        expanded = expanded,
        onToggle = onToggleExpanded,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (subtasks.isEmpty()) {
                    EmptySectionHint(
                        text = "No subtasks yet. Add the first one below.",
                        modifier = Modifier.testTag(TestTags.SUBTASKS_EMPTY_HINT),
                    )
                }

                visibleSubtasks.forEach { task ->
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

                if (!showAll && subtasks.size > 5) {
                    TextButton(
                        onClick = { showAll = true },
                        modifier = Modifier.testTag(TestTags.SUBTASKS_SHOW_ALL),
                    ) {
                        Text("Показать все ${subtasks.size}")
                    }
                }

                // Add-subtask input row — after existing items so new content stays in reading order.
                SubtaskInlineAddRow(
                    revision = subtasks.size,
                    onAddSubtask = onAddSubtask,
                )
            }
        },
    )
}

/**
 * The row that writes a new subtask — an input and a button, side by side.
 *
 * Its own composable so the section stays short enough to read: the row is a
 * block of input plumbing, and the section is about the subtasks.
 */
@Composable
private fun SubtaskInlineAddRow(
    revision: Int,
    onAddSubtask: (String) -> Unit,
) {
    val newTitleState = rememberTextFieldState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            state = newTitleState,
            modifier =
                Modifier
                    .weight(1f)
                    .bringIntoViewOnFocus(revision = revision)
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
            modifier =
                Modifier
                    .touchTarget()
                    .size(40.dp)
                    .testTag(TestTags.SUBTASK_ADD_BUTTON),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add subtask",
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SubtasksSectionEmptyPreview() =
    PreviewThemed(darkTheme = false) {
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
private fun SubtasksSectionWithItemsPreview() =
    PreviewThemed(darkTheme = false) {
        SubtasksSection(
            subtasks =
                persistentListOf(
                    PreviewSamples.task(id = "t2", title = "Collect requirements", completed = true),
                    PreviewSamples.task(id = "t3", title = "Draft the spec"),
                ),
            onToggle = { },
            onDelete = { },
            onOpen = { },
            onAddSubtask = { },
        )
    }
