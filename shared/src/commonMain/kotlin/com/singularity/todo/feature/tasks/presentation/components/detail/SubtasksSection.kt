package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * Section showing direct child tasks of a task.
 * Self-hides when [subtasks] is empty.
 */
@Composable
fun SubtasksSection(
    subtasks: List<Task>,
    onToggle: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subtasks.isEmpty()) return

    val completed = subtasks.count { it.isCompleted }
    val total = subtasks.size

    ExtraSectionCard(
        modifier = modifier,
        icon = { Text("\uD83D\uDCCB", style = MaterialTheme.typography.bodyMedium) },
        label = "Subtasks ($completed/$total)",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
private fun SubtaskRow(task: Task, onToggle: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 2.dp),
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
            modifier = Modifier.height(24.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete subtask",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
