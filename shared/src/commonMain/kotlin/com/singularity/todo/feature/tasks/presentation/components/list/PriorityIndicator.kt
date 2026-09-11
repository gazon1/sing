package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Маленький индикатор приоритета задачи.
 *
 * NONE рисуется контурной звездой приглушённого цвета (как "доступное, но не
 * применённое" действие), остальные уровни — залитой звездой соответствующего
 * цвета. Раздельная функция [priorityColor] переиспользуется в [TaskCheckbox]
 * и [TaskRowContent], чтобы цвет приоритета был согласован по всей строке.
 */
@Composable
fun PriorityIndicator(
    priority: TaskPriority,
    modifier: Modifier = Modifier,
) {
    val color = priorityColor(priority)
    Icon(
        imageVector = if (priority.isActive) Icons.Filled.Star else Icons.Outlined.StarOutline,
        contentDescription = priorityContentDescription(priority),
        tint = color,
        modifier = modifier.size(TaskListSizes.PriorityStar),
    )
}

/** Единый источник правды для цвета приоритета во всём экране. */
fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.HIGH -> TaskListColors.PriorityHigh
    TaskPriority.MEDIUM -> TaskListColors.PriorityMedium
    TaskPriority.LOW -> TaskListColors.PriorityLow
    TaskPriority.NONE -> TaskListColors.PriorityNone
}

private fun priorityContentDescription(priority: TaskPriority): String = when (priority) {
    TaskPriority.HIGH -> "Высокий приоритет"
    TaskPriority.MEDIUM -> "Средний приоритет"
    TaskPriority.LOW -> "Низкий приоритет"
    TaskPriority.NONE -> "Без приоритета"
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun PriorityIndicatorPreview() {
    MaterialTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TaskListSpacing.Md),
            modifier = Modifier.size(200.dp, 40.dp),
        ) {
            PriorityIndicator(TaskPriority.NONE)
            PriorityIndicator(TaskPriority.LOW)
            PriorityIndicator(TaskPriority.MEDIUM)
            PriorityIndicator(TaskPriority.HIGH)
        }
    }
}
