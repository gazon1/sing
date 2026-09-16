package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.PriorityPalette
import com.singularity.todo.feature.tasks.presentation.components.priorityMeta
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

internal val TaskPriority.isActive: Boolean get() = this != TaskPriority.None

/**
 * Маленький индикатор приоритета задачи.
 *
 * NONE рисуется контурной звездой приглушённого цвета (как "доступное, но не
 * применённое" действие), остальные уровни — залитой звездой соответствующего
 * цвета. Раздельная функция [priorityColor] переиспользуется в [TaskCheckbox]
 * и [TaskRowContent], чтобы цвет приоритета был согласован по всей строке.
 */
@Composable
fun PriorityIndicator(priority: TaskPriority, modifier: Modifier = Modifier) {
    val color = priorityColor(priority)
    Icon(
        imageVector = if (priority.isActive) Icons.Filled.Star else Icons.Outlined.StarOutline,
        contentDescription = priorityContentDescription(priority),
        tint = color,
        modifier = modifier.size(TaskListSizes.PriorityStar),
    )
}

/** Единый источник правды для цвета приоритета во всём экране. */
fun priorityColor(priority: TaskPriority): Color = priorityMeta(priority, PriorityPalette.TaskListColors).color

private fun priorityContentDescription(priority: TaskPriority): String = when (priority) {
    TaskPriority.High -> "Высокий приоритет"
    TaskPriority.Medium -> "Средний приоритет"
    TaskPriority.Low -> "Низкий приоритет"
    TaskPriority.None -> "Без приоритета"
    TaskPriority.Urgent -> "Срочный приоритет"
}

@Preview
@Composable
private fun PriorityIndicatorPreview() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TaskListSpacing.Xs),
        modifier = Modifier.size(200.dp, 48.dp),
    ) {
        PriorityIndicator(TaskPriority.None)
        PriorityIndicator(TaskPriority.Low)
        PriorityIndicator(TaskPriority.Medium)
        PriorityIndicator(TaskPriority.High)
        PriorityIndicator(TaskPriority.Urgent)
    }
}
