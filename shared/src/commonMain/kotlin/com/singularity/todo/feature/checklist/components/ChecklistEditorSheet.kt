package com.singularity.todo.feature.checklist.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistEditorSheet(
    taskId: TaskId?,
    items: ImmutableList<ChecklistItem>,
    onAdd: (String) -> Unit,
    onToggle: (ChecklistItemId) -> Unit,
    onDelete: (ChecklistItemId) -> Unit,
    onDismiss: () -> Unit,
) {
    TaskEditorSheetHost(
        title = "Checklist",
        onClose = onDismiss,
        testTag = TestTags.CHECKLIST_SHEET,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ChecklistAddRow(onAdd = onAdd)

            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty()) {
                SelectableText(
                    text = "No checklist items yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { it.id.value }) { item ->
                        // The shared row, not a private copy of it: the task screen
                        // renders checklist items and subtasks with the same widget,
                        // and a second house style here is what made the two lists
                        // look different in the first place.
                        ChecklistItemRow(
                            text = item.title,
                            checked = item.isCompleted,
                            onToggle = { onToggle(item.id) },
                            onDelete = { onDelete(item.id) },
                            tags = ChecklistRowTags.forChecklistItem(id = item.id.value, title = item.title),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistAddRow(onAdd: (String) -> Unit) {
    val textState = rememberTextFieldState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            state = textState,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.CHECKLIST_ADD_INPUT),
            placeholder = { Text("Add item...") },
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = {
                val text = textState.text.toString()
                if (text.isNotBlank()) {
                    onAdd(text.trim())
                    textState.clearText()
                }
            },
            enabled = textState.text.isNotBlank(),
            modifier = Modifier.testTag(TestTags.CHECKLIST_ADD_BUTTON),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChecklistEditorSheetEmptyPreview() = PreviewThemed(darkTheme = false) {
    ChecklistEditorSheet(
        taskId = TaskId("t1"),
        items = persistentListOf(),
        onAdd = { },
        onToggle = { },
        onDelete = { },
        onDismiss = noopClick,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChecklistEditorSheetWithItemsPreview() = PreviewThemed(darkTheme = false) {
    ChecklistEditorSheet(
        taskId = TaskId("t1"),
        items = persistentListOf(
            ChecklistItem(
                id = ChecklistItemId.fromString("cli_1"),
                taskId = "t1",
                title = "Draft the outline",
                isCompleted = true,
            ),
            ChecklistItem(
                id = ChecklistItemId.fromString("cli_2"),
                taskId = "t1",
                title = "Send it to the reviewer",
                isCompleted = false,
            ),
        ),
        onAdd = { },
        onToggle = { },
        onDelete = { },
        onDismiss = noopClick,
    )
}
