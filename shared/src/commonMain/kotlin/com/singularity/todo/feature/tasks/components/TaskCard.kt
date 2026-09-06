package com.singularity.todo.feature.tasks.components

import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.TaskPriority
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.AiActionButton
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.feature.tasks.Task

/**
 * Card representation of a single task. Stateless — every piece of behavior is
 * supplied via [onClick] (whole-row tap) and [actions] (per-button callbacks).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    actions: TaskCardActions = TaskCardActions.Empty,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.taskItem(task.title))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isPinned)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleButton(isCompleted = task.isCompleted, onClick = actions::onToggle, taskTitle = task.title)

            TaskText(task = task, modifier = Modifier.weight(1f))

            PriorityChip(priority = task.priority)

            PinButton(isPinned = task.isPinned, onClick = actions::onPin)
            AiActionButton(onClick = actions::onAiClick)
            DeleteActionButton(onClick = actions::onDelete)
        }
    }
}

@Composable
private fun ToggleButton(isCompleted: Boolean, onClick: () -> Unit, taskTitle: String = "") {
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag(TestTags.taskCheckbox(taskTitle))
    ) {
        Icon(
            imageVector = if (isCompleted) Icons.Filled.Check else Icons.Filled.Star,
            contentDescription = "Toggle complete",
            tint = if (isCompleted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskText(task: Task, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        task.dueDate?.let { date ->
            Text(
                text = date.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinButton(isPinned: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = if (isPinned) "Unpin" else "Pin",
            tint = if (isPinned) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskCardLightPreview() = PreviewThemed(darkTheme = false) {
    TaskCard(
        task = PreviewSamples.task(),
        onClick = {},
        onLongClick = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskCardCompletedDarkPreview() = PreviewThemed(darkTheme = true) {
    TaskCard(
        task = PreviewSamples.task(completed = true),
        onClick = {},
        onLongClick = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskCardPinnedPurpleDarkPreview() = PreviewThemed(
    darkTheme = true,
    accent = com.singularity.todo.core.ui.theme.SingularityAccents.Purple,
) {
    TaskCard(
        task = PreviewSamples.task(pinned = true, priority = TaskPriority.High),
        onClick = {},
        onLongClick = {},
    )
}
