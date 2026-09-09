package com.singularity.todo.feature.tasks.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.tasks.components.TaskDetailActions
import com.singularity.todo.feature.checklist.components.ChecklistItemRow

/**
 * The checklist section of a task detail screen — shows a progress bar,
 * individual checklist items with toggle/delete/promote, and an inline "add item" field.
 *
 * @param items    All checklist items belonging to the current task.
 * @param actions  Packed [TaskDetailActions] callback handler.
 * @param onPromoteChecklist Optional callback to convert a checklist item to a subtask.
 */
@Composable
fun TaskChecklistSection(
    items: List<ChecklistItem>,
    actions: TaskDetailActions,
    onPromoteChecklist: ((ChecklistItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    if (items.isEmpty() && draft.isEmpty()) return

    val done = items.count { it.isCompleted }
    val total = items.size

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Header with progress ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Checklist",
                style = MaterialTheme.typography.titleSmall,
            )
            if (total > 0) {
                Text(
                    "$done/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Progress bar ───────────────────────────────────────────────────────
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Items ──────────────────────────────────────────────────────────────
        items.forEach { item ->
            ChecklistItemRow(
                text = item.title,
                checked = item.isCompleted,
                onToggle = { actions.onToggleChecklistItem(item) },
                onDelete = { actions.onDeleteChecklistItem(item.id) },
                onPromote = onPromoteChecklist?.let { { it(item) } },
            )
        }

        // ── Add item inline ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Add item...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        actions.onAddChecklistItem(draft)
                        draft = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
        }
    }
}
