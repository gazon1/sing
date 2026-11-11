package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.priorityMarkerRimColor
import com.singularity.todo.feature.tasks.presentation.theme.priorityMeta
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.priority_high
import singularity_todo.shared.generated.resources.priority_low
import singularity_todo.shared.generated.resources.priority_medium
import singularity_todo.shared.generated.resources.priority_none
import singularity_todo.shared.generated.resources.priority_urgent

internal val TaskPriority.isActive: Boolean get() = this != TaskPriority.None

/**
 * Маленький индикатор приоритета задачи.
 *
 * NONE рисуется контурной звездой приглушённого цвета (как "доступное, но не
 * применённое" действие), остальные уровни — залитой звездой соответствующего
 * цвета. Раздельная функция [priorityColor] переиспользуется в [TaskCheckbox]
 * и [TaskRowContent], чтобы цвет приоритета был согласован по всей строке.
 *
 * **У звезды есть ободок.** Цвет приоритета — это оттенок, а не свет: янтарный
 * Medium на светлой поверхности даёт около 1.5:1, и глаз не находит маркер в
 * строке. Ободок — тематический цвет, который держит 3:1 к поверхности в обеих
 * темах, и именно он делает маркер различимым. Звезда при этом не растёт:
 * ободок берётся из её же размера.
 */
@Composable
fun PriorityIndicator(
    priority: TaskPriority,
    modifier: Modifier = Modifier,
) {
    val color = priorityColor(priority)
    val star = if (priority.isActive) Icons.Filled.Star else Icons.Outlined.StarOutline
    Box(
        modifier = modifier.size(TaskListSizes.PriorityStar),
        contentAlignment = Alignment.Center,
    ) {
        // The rim: the same star, a little larger, in the theme colour the
        // marker is seen by.
        Icon(
            imageVector = star,
            contentDescription = null,
            tint = priorityMarkerRimColor(),
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = star,
            contentDescription = priorityContentDescription(priority),
            tint = color,
            modifier = Modifier.fillMaxSize().padding(TaskListSizes.PriorityMarkerRim),
        )
    }
}

/**
 * The list's priority tint, shared by the row, the checkbox and the star so a
 * task's priority reads the same wherever it appears on the screen.
 */
fun priorityColor(priority: TaskPriority): Color = priorityMeta(priority).color

@Composable
private fun priorityContentDescription(priority: TaskPriority): String =
    when (priority) {
        TaskPriority.High -> stringResource(Res.string.priority_high)
        TaskPriority.Medium -> stringResource(Res.string.priority_medium)
        TaskPriority.Low -> stringResource(Res.string.priority_low)
        TaskPriority.None -> stringResource(Res.string.priority_none)
        TaskPriority.Urgent -> stringResource(Res.string.priority_urgent)
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
