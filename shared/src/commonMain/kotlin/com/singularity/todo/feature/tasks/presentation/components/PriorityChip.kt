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
import com.singularity.todo.feature.tasks.presentation.theme.PriorityPalette
import com.singularity.todo.feature.tasks.presentation.theme.priorityMeta

/**
 * The chip's own priority tint.
 *
 * Named `priorityChipColor` rather than `priorityColor` because a second
 * `priorityColor` already exists in the `components.list` package, carries
 * different values, and is the one the unit test covers. Two functions of the
 * same name in sibling packages differing only in their palettes is a wrong-import
 * waiting to happen — the compiler will not flag it and the reviewer will not
 * notice.
 */
fun priorityChipColor(priority: TaskPriority): Color =
    priorityMeta(priority, PriorityPalette.PriorityChip).color

@Composable
fun PriorityChip(priority: TaskPriority, modifier: Modifier = Modifier) {
    if (priority == TaskPriority.None) return
    val color = priorityChipColor(priority)
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
