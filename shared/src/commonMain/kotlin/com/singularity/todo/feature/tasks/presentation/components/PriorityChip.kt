package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskPriority

/** Maps a task priority ordinal to its display color. Public for unit testing. */
fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.Low -> Color(0xFF4CAF50)
    TaskPriority.Medium -> Color(0xFFFF9800)
    TaskPriority.High -> Color(0xFFF44336)
    TaskPriority.Urgent -> Color(0xFFE91E63)
    TaskPriority.None -> Color.Unspecified
}

@Composable
fun PriorityChip(priority: TaskPriority, modifier: Modifier = Modifier) {
    if (priority == TaskPriority.None) return
    val color = priorityColor(priority)
    Box(modifier = modifier.padding(start = 4.dp)) {
        Text(
            text = priority.name,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PriorityChipMediumPreview() = PreviewThemed(darkTheme = false) {
    PriorityChip(priority = TaskPriority.Medium)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PriorityChipHighDarkPreview() = PreviewThemed(darkTheme = true) {
    PriorityChip(priority = TaskPriority.High)
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PriorityChipUrgentPreview() = PreviewThemed(darkTheme = false) {
    PriorityChip(priority = TaskPriority.Urgent)
}
