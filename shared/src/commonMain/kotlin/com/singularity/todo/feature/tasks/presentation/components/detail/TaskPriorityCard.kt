package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.tasks.presentation.state.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors

/**
 * Приоритет — частный случай TaskAttributeCard, но с цветовой семантикой:
 * низкий/средний/высокий получают разный цвет иконки, чтобы приоритет
 * считывался с одного взгляда, без чтения текста.
 */
@Composable
fun TaskPriorityCard(
    priority: TaskPriority,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when (priority) {
        TaskPriority.LOW -> TaskColors.PriorityLow
        TaskPriority.MEDIUM -> TaskColors.PriorityMedium
        TaskPriority.HIGH -> TaskColors.PriorityHigh
    }
    TaskAttributeCard(
        icon = Icons.Outlined.ErrorOutline,
        label = priority.label,
        onClick = onClick,
        isActive = true,
        iconTint = color,
        textColor = TaskColors.TextPrimary,
        containerColor = TaskColors.Surface,
        modifier = modifier
    )
}
