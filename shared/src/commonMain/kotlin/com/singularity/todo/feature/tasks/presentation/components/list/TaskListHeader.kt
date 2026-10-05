package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.elevatedSurfaceColor

/**
 * Заголовок экрана: крупное название списка/дня + счётчик активных задач
 * (badge) + быстрые действия.
 *
 * Счётчик показывается как маленький pill-badge рядом с заголовком —
 * это привычный паттерн (Things, TickTick), который сразу даёт ощущение
 * объёма списка без необходимости пересчитывать строки глазами.
 *
 * [subtitle] — необязательная вторая строка под заголовком (например,
 * "12 сентября 2026 · Вс"). Если не нужна — оставить null.
 */
@Composable
fun TaskListHeader(
    title: String,
    taskCount: Int,
    onCalendarClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TaskListSpacing.Lg, vertical = TaskListSpacing.Sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (taskCount > 0) {
                    CountBadge(count = taskCount)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        IconButton(onClick = onCalendarClick) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = "Открыть календарь",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Больше действий",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Маленький pill с числом активных задач. Выровнен по нижней границе
 * заголовка (Bottom + 4dp отступ от baseline через внутренний padding),
 * без хака с `padding(bottom = 3.dp)` на родителе.
 */
@Composable
private fun CountBadge(count: Int) {
    Row(
        modifier = Modifier
            .padding(start = TaskListSpacing.Sm, bottom = 4.dp) // 4dp от baseline заголовка
            .background(elevatedSurfaceColor(), CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun TaskListHeaderPreview() {
    PreviewThemed(darkTheme = true, useSurface = true) {
        Column {
            TaskListHeader(
                title = "Сегодня",
                taskCount = 7,
                onCalendarClick = {},
                onMoreClick = {},
                subtitle = "11 сентября 2026 · Чт",
            )
            TaskListHeader(
                title = "Все задачи",
                taskCount = 0,
                onCalendarClick = {},
                onMoreClick = {},
            )
        }
    }
}
