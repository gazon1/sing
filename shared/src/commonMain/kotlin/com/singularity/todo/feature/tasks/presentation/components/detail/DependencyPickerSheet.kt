package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost

/**
 * Multi-select task dependency picker sheet.
 *
 * Allows the user to pick which tasks this task depends on.
 * Opens from a "Dependencies" card in `TaskDetailViewScreen.extraSections`.
 *
 * @param currentDeps Currently selected dependency IDs for this task.
 * @param availableTasks All tasks available for selection (not trashed, not self).
 * @param onApply Called with the new set of dependency IDs when the user confirms.
 * @param onDismiss Called when the user dismisses or cancels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DependencyPickerSheet(
    currentDeps: Set<TaskId>,
    availableTasks: List<Task>,
    onApply: (Set<TaskId>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentDeps) }

    TaskEditorSheetHost(
        title = "Dependencies",
        onClose = onDismiss,
        onConfirm = { onApply(selected) },
    ) {
        if (availableTasks.isEmpty()) {
            Text(
                text = "No tasks available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = availableTasks,
                    key = { it.id.value },
                ) { task ->
                    DependencyItem(
                        task = task,
                        isChecked = task.id in selected,
                        onToggle = { id ->
                            selected = if (id in selected) {
                                selected - id
                            } else {
                                selected + id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyItem(
    task: Task,
    isChecked: Boolean,
    onToggle: (TaskId) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(task.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle(task.id) },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title.ifBlank { "(Untitled)" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.dueDate != null) {
                Text(
                    text = "Due: ${task.dueDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
