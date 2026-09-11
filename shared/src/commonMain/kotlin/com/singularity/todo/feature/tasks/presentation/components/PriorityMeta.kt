package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.ui.graphics.Color
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors

/**
 * Single source of truth for priority display metadata: color tint + accessibility label.
 *
 * Two palettes coexist by design:
 * - [TaskColors.PriorityHigh] (warm red #EB5757) — used inside TaskDetail screen for "selected priority" tinting.
 * - [TaskListColors.PriorityHigh] (deep red #E5484D) — used in TaskList/row contexts to match list visual rhythm.
 *
 * Pass the appropriate [Palette] to avoid mixing tints across screens.
 */
data class PriorityMeta(
    val color: Color,
    val label: String,
)

enum class PriorityPalette { TaskColors, TaskListColors }

internal fun priorityMeta(priority: TaskPriority, palette: PriorityPalette = PriorityPalette.TaskColors): PriorityMeta {
    val color: Color = when (palette) {
        PriorityPalette.TaskColors -> when (priority) {
            TaskPriority.None -> TaskColors.TextSecondary
            TaskPriority.Low -> TaskColors.PriorityLow
            TaskPriority.Medium -> TaskColors.PriorityMedium
            TaskPriority.High -> TaskColors.PriorityHigh
            TaskPriority.Urgent -> TaskColors.PriorityUrgent
        }
        PriorityPalette.TaskListColors -> when (priority) {
            TaskPriority.None -> TaskListColors.PriorityNone
            TaskPriority.Low -> TaskListColors.PriorityLow
            TaskPriority.Medium -> TaskListColors.PriorityMedium
            TaskPriority.High -> TaskListColors.PriorityHigh
            TaskPriority.Urgent -> TaskListColors.PriorityUrgent
        }
    }
    val label: String = when (priority) {
        TaskPriority.None -> "No priority"
        TaskPriority.Low -> "Low priority"
        TaskPriority.Medium -> "Medium priority"
        TaskPriority.High -> "High priority"
        TaskPriority.Urgent -> "Urgent"
    }
    return PriorityMeta(color, label)
}
