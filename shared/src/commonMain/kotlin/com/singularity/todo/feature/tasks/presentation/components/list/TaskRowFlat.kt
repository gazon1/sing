package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Плоский стиль строки — без своей подложки, разделение достигается тонкой
 * линией снизу. Плотнее и легче для длинных списков (referenced UI: Todoist,
 * Things, Apple Reminders).
 *
 * Использовать вместе с [androidx.compose.foundation.lazy.LazyColumn] без
 * [androidx.compose.foundation.layout.Arrangement.spacedBy] — разделители сами
 * создают ритм, лишний spacing будет визуально дублировать эту функцию.
 *
 * [showDivider] позволяет выключить линию для последней строки (или вручную,
 * если снаружи уже отрисован свой разделитель).
 */
@Composable
fun TaskRowFlat(
    task: TaskUi,
    onToggleCompleted: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indentLevel: Int = 0,
    showDivider: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indentPadding = if (indentLevel > 0) {
        Modifier.padding(start = (24 * indentLevel).dp)
    } else {
        Modifier
    }

    Column(modifier = modifier.fillMaxWidth().then(indentPadding)) {
        TaskRowContent(
            task = task,
            onToggleCompleted = onToggleCompleted,
            modifier = Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = TaskListSpacing.Md + TaskListSpacing.Xs, horizontal = TaskListSpacing.Lg),
        )

        if (showDivider) {
            HorizontalDivider(
                thickness = TaskListSizes.DividerThickness,
                color = TaskListColors.Divider,
                modifier = Modifier.padding(start = 52.dp), // выравниваем по началу текста, не по краю
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14, widthDp = 360)
@Composable
private fun TaskRowFlatPreview() {
    MaterialTheme {
        Column {
            TaskRowFlat(
                task = TaskUi(
                    id = TaskId.fromString("1"),
                    title = "Позвонить родителям в сб или вс",
                    project = "Семья",
                    dueLabel = "Сб, 05 сент 2026",
                    isRecurring = true,
                ),
                onToggleCompleted = {},
                onClick = {},
            )
            TaskRowFlat(
                task = TaskUi(
                    id = TaskId.fromString("2"),
                    title = "Отправить заявку на баллы фитмост от гпб",
                    project = "Финансы",
                    dueLabel = "Пн, 18 мая 2026",
                    isRecurring = true,
                    priority = TaskPriority.High,
                ),
                onToggleCompleted = {},
                onClick = {},
            )
            TaskRowFlat(
                task = TaskUi(
                    id = TaskId.fromString("child-1"),
                    title = "Подзадача: черновик",
                    project = null,
                    dueLabel = "Пн, 10 нояб 2025",
                    isCompleted = false,
                    parentId = "parent-1",
                    indentLevel = 1,
                ),
                onToggleCompleted = {},
                onClick = {},
                showDivider = false,
            )
        }
    }
}
