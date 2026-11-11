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
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.TaskSemanticColors
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * Вторая строка карточки задачи: дата, отметки, проект.
 *
 * Состав собирается динамически: если нет даты — показываем только проект
 * (или ничего), без висящего разделителя в пустоту. Каждый блок ограничен
 * по ширине с `TextOverflow.Ellipsis`, чтобы длинный проект ломал верстку
 * только своего участка, а не всей строки.
 *
 * **Порядок — это иерархия.** Дата идёт первой, проект — последним и единственным,
 * кто отдаёт ширину: строка меряет своих детей по очереди, поэтому дата
 * получает свою ширину раньше, чем отметки и проект заберут что-то у неё.
 * Длинное название проекта ужимается многоточием, дата — нет.
 *
 * **Маркер серии в строке — иконка без слова.** Слово «Series · 5 dates» было
 * самым широкым потребителем ширины на линии: вместе с датой и тремя отметками
 * оно съедало колонку целиком, и проект — единственный, кто уступает, — получал
 * ноль вместо многоточия. Теперь маркер рисуется иконкой, а счётчик остаётся в
 * его описании для скринридера.
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
    /** The project's ARGB colour, or null when there is no project. */
    projectColor: Int? = null,
    modifier: Modifier = Modifier,
) {
    // One fact, four sources: the row shows nothing when there is nothing to show.
    // The repeat badge is a marking now, so the marks cover it — but only the
    // ones the row draws: a mark the row says another way leaves an empty line.
    val hasSomethingToShow =
        dueLabel != null || project != null ||
            visibleMarkings(markings).isNotEmpty() || seriesDates != null
    if (!hasSomethingToShow) return

    val isOverdue = TaskMarking.Overdue in markings
    // The marks the row says another way draw nothing, so they cannot be what
    // separates the project from what came before it.
    val hasVisibleMark = visibleMarkings(markings).isNotEmpty()
    // What stands between the project and the left edge of the row: without it
    // the separator would hang on empty space.
    val somethingBeforeProject = dueLabel != null || hasVisibleMark || seriesDates != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
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

        if (dueLabel != null && (hasVisibleMark || seriesDates != null)) {
            Spacer(Modifier.width(TaskListSpacing.Xs))
        }

        TaskMarkingsRow(markings = markings)

        if (seriesDates != null) {
            // The icon alone: the marker's word ("Series · 5 dates") is the
            // widest thing on the line, and the width belongs to the date and
            // the project. The count stays in the icon's description.
            SeriesMarker(isSeries = true, dates = seriesDates)
        }

        if (project != null && somethingBeforeProject) {
            MetaSeparator()
        }

        project?.let { name ->
            // The project is last and the only child that gives up width: it is
            // the one field that may be cut short when the row runs out of room.
            ProjectBadge(
                name = name,
                color = projectColor,
                modifier = Modifier.weight(1f, fill = false),
            )
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
private fun ProjectBadge(
    name: String,
    color: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (color != null) {
            Box(
                modifier =
                    Modifier
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
            TaskMetaRow(
                "Сб, 05 сен 2026",
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
                projectColor = 0xFF6A4C93.toInt(),
            )
        }
    }
}
