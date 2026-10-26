package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.components.SeriesMarker
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor
import com.singularity.todo.feature.tasks.presentation.theme.TaskSemanticColors

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
    isBlocked: Boolean = false, // MR-1: blocked by incomplete dependencies
    /** How many dates the series of this row has; null when it stands alone. */
    seriesDates: Int? = null,
    /** The project's ARGB colour, or null when there is no project. */
    projectColor: Int? = null,
    modifier: Modifier = Modifier,
) {
    // One fact, several sources: the row shows nothing when there is nothing to show.
    // The repeat badge is one of them — a repeating task with no date, no project, not
    // blocked and not in a series still has something to say.
    val hasSomethingToShow = dueLabel != null || project != null || isBlocked || seriesDates != null || isRecurring
    if (!hasSomethingToShow) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (isBlocked) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = "Задача заблокирована зависимостями",
                tint = mutedTextColor(),
                modifier = Modifier.size(TaskListSizes.MetaIcon),
            )
            Spacer(Modifier.width(TaskListSpacing.Xs))
        }

        if (isRecurring) {
            Icon(
                imageVector = Icons.Default.Repeat,
                contentDescription = "Повторяющаяся задача",
                tint = mutedTextColor(),
                modifier = Modifier.size(TaskListSizes.MetaIcon),
            )
            Spacer(Modifier.width(TaskListSpacing.Xs))
        }

        if (seriesDates != null) {
            SeriesMarker(isSeries = true, dates = seriesDates)
            Spacer(Modifier.width(TaskListSpacing.Xs))
        }

        dueLabel?.let {
            Text(
                text = it,
                color = if (isOverdue) TaskSemanticColors.Danger else mutedTextColor(),
                fontSize = 12.sp,
                fontWeight = if (isOverdue) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (dueLabel != null && project != null) {
            MetaSeparator()
        }
        project?.let { name ->
            ProjectBadge(name = name, color = projectColor)
        }
    }
}

/**
 * Проект как бейдж: цветная точка проекта и его имя.
 *
 * Цвет — проекта ([color], ARGB); текст — темы. Это то, что делает проекты
 * различимыми с одного взгляда, а не только по имени. Имя сокращается
 * многоточием и не вытесняет дату из строки (FR-031).
 */
@Composable
private fun ProjectBadge(name: String, color: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Box(
                modifier = Modifier
                    .size(TaskListSizes.MetaIcon)
                    .clip(CircleShape)
                    .background(Color(color)),
            )
            Spacer(Modifier.width(TaskListSpacing.Xxs))
        }
        Text(
            text = name,
            color = mutedTextColor(),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
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
        color = mutedTextColor(),
        fontSize = 12.sp,
    )
    Spacer(Modifier.width(TaskListSpacing.Xs))
}

@Preview(showBackground = true)
@Composable
private fun TaskMetaRowPreview() {
    PreviewThemed(darkTheme = true, useSurface = true) {
        Column(verticalArrangement = Arrangement.spacedBy(TaskListSpacing.Md)) {
            TaskMetaRow("Сб, 05 сент 2026", "Семья", isRecurring = true, isOverdue = false)
            TaskMetaRow("Пн, 12 янв 2026", "Блог github pages", isRecurring = true, isOverdue = true)
            TaskMetaRow("Пн, 10 нояб 2025", null, isRecurring = false, isOverdue = true)
            TaskMetaRow(
                null,
                "Очень длинное название проекта которое точно не влезет в строку",
                isRecurring = false,
                isOverdue = false,
                projectColor = 0xFF6A4C93.toInt(),
            )
        }
    }
}
