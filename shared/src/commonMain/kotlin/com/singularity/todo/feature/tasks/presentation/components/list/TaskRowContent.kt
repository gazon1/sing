package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Общее "нутро" строки задачи: чекбокс + приоритет + заголовок + мета.
 *
 * И [TaskRowFlat], и [TaskRowCard] используют этот композабл — они отличаются
 * только внешней подложкой (разделитель vs карточка с тенью), а не структурой
 * контента. Это исключает дублирование логики между двумя стилями.
 *
 * Заголовок ограничен двумя строками с эллипсисом — это сознательное
 * ограничение: длинный заголовок должен сворачиваться, а не выталкивать
 * мета-строку за пределы экрана или ломать высоту строки.
 */
@Composable
fun TaskRowContent(task: TaskUi, onToggleCompleted: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth(),
    ) {
        TaskCheckbox(
            isChecked = task.isCompleted,
            onCheckedChange = { onToggleCompleted() },
            accentColor = priorityColor(task.priority),
            // The card variant tags its own toggle; the flat agenda row has to carry the
            // same tag here, or completion cannot be driven from UI automation.
            modifier = Modifier.testTag(TestTags.taskCheckbox(task.title)),
        )

        Spacer(Modifier.width(TaskListSpacing.Md + TaskListSpacing.Xs)) // 14dp — выравнивание текста по сетке

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                if (task.priority.isActive && !task.isCompleted) {
                    // Сдвигаем иконку вниз на 2dp, чтобы она визуально стояла
                    // на baseline первой строки текста (стандартный приём
                    // для иконок рядом с многострочным текстом).
                    PriorityIndicator(
                        task.priority,
                        modifier = Modifier.padding(top = TaskListSpacing.Xxs),
                    )
                    Spacer(Modifier.width(TaskListSpacing.Xs + TaskListSpacing.Xxs))
                }
                Text(
                    text = task.title,
                    color = if (task.isCompleted) TaskListColors.TextTertiary else TaskListColors.TextPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (task.dueLabel != null || task.project != null || task.isBlocked) {
                Spacer(Modifier.height(TaskListSpacing.Xs))
                TaskMetaRow(
                    dueLabel = task.dueLabel,
                    project = task.project ?: "Без проекта",
                    isRecurring = task.isRecurring,
                    isOverdue = task.isOverdue && !task.isCompleted,
                    isBlocked = task.isBlocked && !task.isCompleted,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14, widthDp = 360)
@Composable
private fun TaskRowContentPreview() {
    MaterialTheme {
        Column {
            TaskRowContent(
                task = TaskUi(
                    id = TaskId.fromString("1"),
                    title = "Позвонить родителям в сб или вс",
                    project = "Семья",
                    dueLabel = "Сб, 05 сент 2026",
                    isRecurring = true,
                    priority = TaskPriority.Medium,
                ),
                onToggleCompleted = {},
            )
            TaskRowContent(
                task = TaskUi(
                    id = TaskId.fromString("2"),
                    title = "Очень длинный заголовок задачи который занимает две строки и должен свернуться с эллипсисом",
                    project = null,
                    dueLabel = "Пн, 10 нояб 2025",
                    isCompleted = true,
                ),
                onToggleCompleted = {},
            )
            TaskRowContent(
                task = TaskUi(
                    id = TaskId.fromString("3"),
                    title = "Просроченная задача с высоким приоритетом",
                    project = "Финансы",
                    dueLabel = "Вчера",
                    priority = TaskPriority.High,
                    isOverdue = true,
                ),
                onToggleCompleted = {},
            )
        }
    }
}
