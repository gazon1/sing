package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * "Напомнить / Повторять / Крайний срок" — раньше это были голые строки без
 * фона, визуально "теряющиеся" между карточками. Оборачиваем в одну
 * Surface, чтобы группа читалась как единый логический блок (паттерн
 * "grouped list" из iOS/Material — уместен и здесь).
 */
@Composable
fun TaskReminderGroup(
    reminder: String?,
    repeatRule: String?,
    deadline: String?,
    onReminderClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onDeadlineClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TaskColors.Surface,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            ReminderGroupRow(
                icon = Icons.Outlined.NotificationsNone,
                label = "Напомнить",
                value = reminder,
                onClick = onReminderClick
            )
            RowDivider()
            ReminderGroupRow(
                icon = Icons.Default.Refresh,
                label = "Повторять",
                value = repeatRule,
                onClick = onRepeatClick
            )
            RowDivider()
            ReminderGroupRow(
                icon = Icons.Outlined.Flag,
                label = "Крайний срок",
                value = deadline,
                onClick = onDeadlineClick
            )
        }
    }
}

@Composable
private fun ReminderGroupRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TaskSpacing.cardPaddingHorizontal, vertical = TaskSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (value != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
            modifier = Modifier.size(TaskSpacing.iconSize)
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = value ?: label,
            color = if (value != null) TaskColors.TextPrimary else TaskColors.TextSecondary,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TaskColors.TextPlaceholder,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = TaskColors.Outline,
        thickness = 1.dp,
        modifier = Modifier.padding(start = TaskSpacing.cardPaddingHorizontal + TaskSpacing.iconSize + TaskSpacing.lg)
    )
}
