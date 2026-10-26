package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.components.SeriesMarker
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor
import com.singularity.todo.feature.tasks.presentation.theme.TaskSemanticColors
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * Вторая строка карточки задачи: [метки] дата · проект.
 *
 * Состав собирается динамически: если нет даты — показываем только проект
 * (или ничего), без висящего разделителя в пустоту. Каждый блок ограничен
 * по ширине с `TextOverflow.Ellipsis`, чтобы длинный проект ломал верстку
 * только своего участка, а не всей строки.
 *
 * The marks are a set now, not three booleans: the row that had one slot for
 * "is this special" could show a blocked task or an overdue one, not both.
 * [TaskMarkingsRow] renders every mark that applies.
 */
@Composable
fun TaskMetaRow(
    dueLabel: String?,
    project: String?,
    markings: ImmutableSet<TaskMarking>,
    /** How many dates the series of this row has; null when it stands alone. */
    seriesDates: Int? = null,
    modifier: Modifier = Modifier,
) {
    // One fact, four sources: the row shows nothing when there is nothing to show.
    val hasSomethingToShow = dueLabel != null || project != null ||
        markings.isNotEmpty() || seriesDates != null
    if (!hasSomethingToShow) return

    val isOverdue = TaskMarking.Overdue in markings

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        TaskMarkingsRow(markings = markings)

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
        project?.let {
            Text(
                text = it,
                color = mutedTextColor(),
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
            TaskMetaRow(
                "Сб, 05 сент 2026",
                "Семья",
                persistentSetOf(TaskMarking.Recurring),
            )
            TaskMetaRow(
                "Пн, 12 янв 2026",
                "Блог github pages",
                persistentSetOf(TaskMarking.Recurring, TaskMarking.Overdue),
            )
            TaskMetaRow(
                "Пн, 10 нояб 2025",
                null,
                persistentSetOf(TaskMarking.Overdue),
            )
            TaskMetaRow(
                null,
                "Очень длинное название проекта которое точно не влезет в строку",
                persistentSetOf(
                    TaskMarking.InProgress,
                    TaskMarking.Blocked,
                    TaskMarking.FollowUp,
                    TaskMarking.Pinned,
                ),
            )
            TaskMetaRow(null, "Без проекта", persistentSetOf())
        }
    }
}
