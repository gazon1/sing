package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Вторая строка карточки задачи: [повтор?] дата · проект.
 *
 * Состав собирается динамически: если нет даты — показываем только проект
 * (или ничего), без висящего разделителя в пустоту. Каждый блок ограничен
 * по ширине с `TextOverflow.Ellipsis`, чтобы длинный проект ломал верстку
 * только своего участка, а не всей строки.
 */
@Composable
fun TaskMetaRow(
    dueLabel: String?,
    project: String?,
    isRecurring: Boolean,
    isOverdue: Boolean,
    modifier: Modifier = Modifier,
) {
    if (dueLabel == null && project == null) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (isRecurring) {
            Icon(
                imageVector = Icons.Default.Repeat,
                contentDescription = "Повторяющаяся задача",
                tint = TaskListColors.TextTertiary,
                modifier = Modifier.size(TaskListSizes.MetaIcon),
            )
            Spacer(Modifier.width(TaskListSpacing.Xs))
        }

        dueLabel?.let {
            Text(
                text = it,
                color = if (isOverdue) TaskListColors.Danger else TaskListColors.TextTertiary,
                fontSize = 12.sp,
                fontWeight = if (isOverdue) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (dueLabel != null && project != null) {
            MetaSeparator()
        }
        project?.let {
            Text(
                text = it,
                color = TaskListColors.TextTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Маленькая точка-разделитель вместо строкового "  /  ".
 * Геометрия фиксирована (3.dp), цвет — TextTertiary; никаких подгонок паддингами.
 */
@Composable
private fun MetaSeparator() {
    Spacer(Modifier.width(TaskListSpacing.Xs))
    Text(
        text = "·",
        color = TaskListColors.TextTertiary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.width(TaskListSpacing.Xs))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun TaskMetaRowPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(TaskListSpacing.Md)) {
            TaskMetaRow("Сб, 05 сент 2026", "Семья", isRecurring = true, isOverdue = false)
            TaskMetaRow("Пн, 12 янв 2026", "Блог github pages", isRecurring = true, isOverdue = true)
            TaskMetaRow("Пн, 10 нояб 2025", null, isRecurring = false, isOverdue = true)
            TaskMetaRow(null, "Очень длинное название проекта которое точно не влезет в строку", isRecurring = false, isOverdue = false)
            TaskMetaRow(null, "Без проекта", isRecurring = false, isOverdue = false)
        }
    }
}
