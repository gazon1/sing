package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.PriorityPalette
import com.singularity.todo.feature.tasks.presentation.components.priorityMeta
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors

/**
 * Приоритет — частный случай TaskAttributeCard, но с цветовой семантикой:
 * цвет иконки отражает уровень приоритета, чтобы читаться с одного взгляда.
 *
 * ВЫЗЫВАТЬ ТОЛЬКО когда priority != None; снаружи сделать if-guard.
 */
@Composable
fun TaskPriorityCard(priority: TaskPriority, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (color, label) = priorityMeta(priority, PriorityPalette.TaskColors)
    TaskAttributeCard(
        icon = Icons.Outlined.ErrorOutline,
        label = label,
        onClick = onClick,
        isActive = true,
        iconTint = color,
        textColor = TaskColors.TextPrimary,
        containerColor = TaskColors.Surface,
        modifier = modifier,
    )
}
