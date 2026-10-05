package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.presentation.theme.TaskListShapes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Горизонтальные фильтр-чипы: Все / Активные / Выполненные.
 *
 * Кастомная реализация (а не [androidx.compose.material3.FilterChip]) —
 * потому что в Material 3 чип-фон жёстко привязан к surfaceVariant, который
 * плохо ложится на наш тёмный кастом. Чипы здесь — это просто pill с
 * активной/неактивной заливкой и анимацией перехода цвета.
 *
 * Скроллятся горизонтально, чтобы влезать даже на узких экранах при
 * потенциальном расширении ("Сегодня / Неделя / Месяц / Позже").
 */
@Composable
fun TaskFilterChips(
    selected: TaskStatus,
    onSelect: (TaskStatus) -> Unit,
    counts: Map<TaskStatus, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val filters = listOf(
        TaskStatus.All to "Все",
        TaskStatus.Active to "Активные",
        TaskStatus.Completed to "Выполненные",
    )

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TaskListSpacing.Sm),
        contentPadding = PaddingValues(
            horizontal = TaskListSpacing.Lg,
            vertical = TaskListSpacing.Sm,
        ),
    ) {
        items(filters, key = { it.first }) { (filter, label) ->
            FilterChip(
                label = label,
                count = counts[filter] ?: 0,
                isSelected = filter == selected,
                onClick = { onSelect(filter) },
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, isSelected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        label = "chipBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "chipText",
    )

    Row(
        modifier = Modifier
            .clip(TaskListShapes.ChipRadius)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = TaskListSpacing.Md + TaskListSpacing.Xs, vertical = TaskListSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (count > 0) {
            Text(
                text = "  $count",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14, widthDp = 360)
@Composable
private fun TaskFilterChipsPreview() {
    MaterialTheme {
        Row {
            TaskFilterChips(
                selected = TaskStatus.All,
                onSelect = {},
                counts = mapOf(
                    TaskStatus.All to 12,
                    TaskStatus.Active to 7,
                    TaskStatus.Completed to 5,
                ),
            )
        }
    }
}
