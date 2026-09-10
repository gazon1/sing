package com.singularity.todo.feature.tasks.presentation.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.presentation.components.TaskDetailActions

/**
 * Displays the 1-level list of child sub-tasks beneath a parent task.
 * Shows a progress indicator, each subtask as a tappable row (checkbox + title),
 * and an inline "Add subtask" field.
 *
 * @param subtasks    Direct child tasks of the parent.
 * @param actions    Packed [TaskDetailActions] callback handler.
 */
@Composable
fun TaskSubtasksSection(
    subtasks: List<Task>,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    if (subtasks.isEmpty()) return

    val done = subtasks.count { it.isCompleted }
    val total = subtasks.size

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Subtasks",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "$done/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Progress bar
        if (total > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { done.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Subtask rows
        subtasks.forEach { child ->
            SubtaskRow(
                task = child,
                onToggle = { actions.onToggleSubtask(child) },
                onClick = { actions.onNavigateToSubtask(child.id) },
                onDelete = { actions.onDeleteSubtask(child) },
            )
        }
    }
}

@Composable
private fun SubtaskRow(
    task: Task,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.width(36.dp)) {
            Icon(
                imageVector = if (task.isCompleted) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.Circle
                },
                contentDescription = if (task.isCompleted) "Completed" else "Not completed",
                tint = if (task.isCompleted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
            color = if (task.isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More options",
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }

        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = "Open subtask",
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
