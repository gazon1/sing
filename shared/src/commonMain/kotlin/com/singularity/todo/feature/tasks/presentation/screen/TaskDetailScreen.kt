package com.singularity.todo.feature.tasks.presentation.screen



import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskAttributeCard
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskChecklistCard
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskCounterCard
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskCreationTopBar
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDescriptionField
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskMoreOptionsRow
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskPriorityCard
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskReminderGroup
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSaveBar
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskTitleRow
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreationEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreationState
import com.singularity.todo.feature.tasks.presentation.state.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

////**
// * Экран создания/редактирования задачи.
// *
// * Сам экран — тонкий оркестратор: держит верхнеуровневый layout и
// * прокидывает state/события в дочерние компоненты. Вся визуальная и
// * интерактивная логика — в components/*, вся модель данных — в state/*.
// * Это позволяет тестировать и превьюить каждый блок изолированно и
// * переиспользовать TaskAttributeCard/TaskCounterCard на других экранах.
// //*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreationScreen(
    state: TaskCreationState,
    onEvent: (TaskCreationEvent) -> Unit
) {
    Scaffold(
        topBar = {
            TaskCreationTopBar(
                onBackClick = { onEvent(TaskCreationEvent.BackClicked) },
                onMoreClick = { onEvent(TaskCreationEvent.MoreMenuClicked) }
            )
        },
        bottomBar = {
            TaskSaveBar(
                isEnabled = state.isSaveEnabled,
                onSaveClick = { onEvent(TaskCreationEvent.SaveClicked) }
            )
        },
        containerColor = TaskColors.Background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaskSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(TaskSpacing.md)
        ) {
            TaskTitleRow(
                title = state.title,
                isCompleted = state.isCompleted,
                onTitleChange = { onEvent(TaskCreationEvent.TitleChanged(it)) },
                onCheckToggle = { onEvent(TaskCreationEvent.CheckboxToggled) }
            )

            TaskDescriptionField(
                description = state.description,
                onDescriptionChange = { onEvent(TaskCreationEvent.DescriptionChanged(it)) }
            )

            TaskChecklistCard(
                itemCount = state.checklistCount,
                onClick = { onEvent(TaskCreationEvent.ChecklistClicked) }
            )

            TaskAttributeCard(
                icon = Icons.AutoMirrored.Filled.CallSplit,
                label = state.projectName ?: "Без проекта",
                isActive = state.projectName != null,
                onClick = { onEvent(TaskCreationEvent.ProjectClicked) }
            )

            TaskPriorityCard(
                priority = state.priority,
                onClick = { onEvent(TaskCreationEvent.PriorityClicked) }
            )

            TaskAttributeCard(
                icon = Icons.Outlined.CalendarToday,
                label = state.dueDate.toLabel(),
                isActive = state.dueDate != DueDateOption.None,
                onClick = { onEvent(TaskCreationEvent.DueDateClicked) },
                trailingContent = if (state.dueDate != DueDateOption.None) {
                    {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Сбросить дату",
                            tint = TaskColors.TextSecondary,
                            modifier = Modifier
                                .padding(4.dp)
                        )
                    }
                } else null
            )

            TaskReminderGroup(
                reminder = state.reminder,
                repeatRule = state.repeatRule,
                deadline = state.deadline,
                onReminderClick = { onEvent(TaskCreationEvent.ReminderClicked) },
                onRepeatClick = { onEvent(TaskCreationEvent.RepeatClicked) },
                onDeadlineClick = { onEvent(TaskCreationEvent.DeadlineClicked) }
            )

            TaskCounterCard(
                icon = Icons.Filled.Checklist,
                label = "Подзадачи",
                count = state.subtasksCount,
                onClick = { onEvent(TaskCreationEvent.SubtasksClicked) }
            )

            TaskCounterCard(
                icon = Icons.Default.AttachFile,
                label = "Файлы",
                count = state.attachmentsCount,
                onClick = { onEvent(TaskCreationEvent.FilesClicked) }
            )

            TaskMoreOptionsRow(
                onClick = { onEvent(TaskCreationEvent.MoreOptionsClicked) }
            )

            Spacer(modifier = Modifier.height(TaskSpacing.xl))
        }
    }
}

private fun DueDateOption.toLabel(): String = when (this) {
    DueDateOption.None -> "Добавить дату"
    DueDateOption.Today -> "Сегодня"
    DueDateOption.Tomorrow -> "Завтра"
    is DueDateOption.Custom -> label
}

@Preview
@Composable
private fun TaskCreationScreenPreview() {
    MaterialTheme {
        TaskCreationScreen(
            state = TaskCreationState(
                title = "",
                description = "",
                priority = TaskPriority.MEDIUM,
                dueDate = DueDateOption.Today,
                subtasksCount = 0,
                attachmentsCount = 0,
                isSaveEnabled = false
            ),
            onEvent = {}
        )
    }
}
