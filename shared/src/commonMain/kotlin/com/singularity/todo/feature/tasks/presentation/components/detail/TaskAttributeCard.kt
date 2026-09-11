package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Карточка-строка для основных атрибутов задачи (проект, приоритет, дата и т.д.).
 *
 * @param isActive true, если атрибут задан пользователем — тогда используем
 * акцентный фон/цвет вместо нейтрального, чтобы "заполненные" поля визуально
 * отличались от пустых (в исходном варианте это работало не всегда).
 */
@Composable
fun TaskAttributeCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    iconTint: Color = if (isActive) TaskColors.AccentBlue else TaskColors.TextSecondary,
    textColor: Color = if (isActive) TaskColors.TextPrimary else TaskColors.TextSecondary,
    containerColor: Color = if (isActive) TaskColors.AccentBlueContainer else TaskColors.Surface,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(
                    horizontal = TaskSpacing.cardPaddingHorizontal,
                    vertical = TaskSpacing.cardPaddingVertical
                )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(TaskSpacing.iconSize)
            )
            Spacer(Modifier.width(TaskSpacing.lg))
            Text(
                text = label,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
