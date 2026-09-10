package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.checklist.components.ChecklistItemRow

/**
 * Checklist section with inline add input and existing items.
 * Uses the shared [ChecklistItemRow] to avoid duplication.
 */
@Composable
fun TaskEditorChecklistSection(
    items: List<ChecklistItemUi>,
    newItemText: String,
    onNewItemChange: (String) -> Unit,
    onAddItem: () -> Unit,
    onToggleItem: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Checklist",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(TestTags.TASK_EDITOR_CHECKLIST),
        )

        Spacer(Modifier.height(4.dp))

        // Add new item
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newItemText,
                onValueChange = onNewItemChange,
                placeholder = { Text("Add checklist item…") },
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.TASK_EDITOR_CHECKLIST_ADD_INPUT),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAddItem() }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onAddItem,
                modifier = Modifier.testTag(TestTags.TASK_EDITOR_CHECKLIST_ADD_BUTTON),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        }

        // Existing items
        items.forEach { item ->
            ChecklistItemRow(
                text = item.title,
                checked = item.isCompleted,
                onToggle = { onToggleItem(item.id) },
                onDelete = { onDeleteItem(item.id) },
            )
        }
    }
}

// ChecklistItemUi is defined in TaskEditorState.kt and re-exported here for convenience
// of components package consumers.
typealias ChecklistItemUi = com.singularity.todo.feature.tasks.domain.model.ChecklistItemUi
