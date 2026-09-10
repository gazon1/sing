package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Подзадачи/Файлы: показываем счётчик, если элементы уже есть — иначе
 * просто "+" с лейблом. Раньше плюс был всегда, независимо от состояния,
 * и не давал понять, есть ли уже что-то добавлено.
 */
@Composable
fun TaskCounterCard(
    icon: ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TaskAttributeCard(
        icon = icon,
        label = label,
        onClick = onClick,
        isActive = count > 0,
        modifier = modifier,
        trailingContent = {
            if (count > 0) {
                CounterBadge(count)
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Добавить",
                    tint = TaskColors.TextSecondary
                )
            }
        }
    )
}

@Composable
private fun CounterBadge(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = count.toString(),
            color = TaskColors.TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.width(TaskSpacing.sm))
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Добавить ещё",
            tint = TaskColors.TextSecondary
        )
    }
}
