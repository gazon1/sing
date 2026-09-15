package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.TimePickerSheet
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.feature.tasks.presentation.state.CreateActiveSheet
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Content for TaskDetail Create mode.
 * Uses TaskAttributeCard for priority with trailing X to clear (no hide-if-None UX).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateContent(
    state: TaskCreateUiState,
    snackbarHost: @Composable () -> Unit,
    onIntent: (TaskCreateIntent) -> Unit,
    onBack: () -> Unit,
) {
    var activeSheet by remember { mutableStateOf<CreateActiveSheet?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TaskCreationTopBar(
                onBackClick = onBack,
                onMoreClick = { showMenu = true },
            )
        },
        bottomBar = {
            TaskSaveBar(
                isEnabled = state.isSaveEnabled,
                isLoading = state.isSaving,
                onSaveClick = { onIntent(TaskCreateIntent.SaveClicked) },
            )
        },
        snackbarHost = snackbarHost,
        containerColor = TaskColors.Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaskSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(TaskSpacing.md),
        ) {
            TaskTitleRow(
                title = state.draft.title,
                isCompleted = false,
                onTitleChange = { onIntent(TaskCreateIntent.TitleChanged(it)) },
                onCheckToggle = { },
            )

            TaskDescriptionField(
                description = state.draft.description,
                onDescriptionChange = { onIntent(TaskCreateIntent.DescriptionChanged(it)) },
            )

            // Priority — always shown, trailing X when not None
            TaskAttributeCard(
                icon = Icons.Outlined.Flag,
                label = priorityLabel(state.draft.priority),
                isActive = state.draft.priority != TaskPriority.None,
                onClick = { activeSheet = CreateActiveSheet.Priority },
                trailingContent = if (state.draft.priority != TaskPriority.None) {
                    {
                        IconButton(
                            onClick = {
                                onIntent(TaskCreateIntent.SetPriority(TaskPriority.None))
                                activeSheet = null
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Сбросить приоритет",
                                tint = TaskColors.TextSecondary,
                            )
                        }
                    }
                } else null,
            )

            // Due date — always shown, trailing X when not None
            TaskAttributeCard(
                icon = Icons.Outlined.CalendarToday,
                label = dueDateLabel(state.draft.dueDate),
                isActive = state.draft.dueDate != DueDateOption.None,
                onClick = { activeSheet = CreateActiveSheet.Date },
                trailingContent = if (state.draft.dueDate != DueDateOption.None) {
                    {
                        IconButton(
                            onClick = { onIntent(TaskCreateIntent.DueDateCleared) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Сбросить дату",
                                tint = TaskColors.TextSecondary,
                            )
                        }
                    }
                } else null,
            )

            Spacer(modifier = Modifier.height(TaskSpacing.xl))
        }
    }

    // ─── Sheets ───────────────────────────────────────────────────────────
    when (activeSheet) {
        CreateActiveSheet.Date -> DatePickerSheet(
            initialDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
            onDateSelected = { date ->
                onIntent(TaskCreateIntent.SetDueDate(date))
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
        )
        CreateActiveSheet.Time -> TimePickerSheet(
            initialTime = state.draft.dueTime,
            onTimeSelected = { time ->
                onIntent(TaskCreateIntent.SetDueTime(time))
                activeSheet = null
            },
            onDismiss = { activeSheet = null },
        )
        CreateActiveSheet.Priority -> TaskEditorSheetHost(
            title = "Приоритет",
            onClose = { activeSheet = null },
        ) {
            TaskEditorPrioritySheet(
                selected = state.draft.priority,
                onSelect = { priority ->
                    onIntent(TaskCreateIntent.SetPriority(priority))
                    activeSheet = null
                },
            )
        }
        CreateActiveSheet.Kind,
        CreateActiveSheet.Project,
        CreateActiveSheet.Tags,
        CreateActiveSheet.Reminder,
        CreateActiveSheet.Attachment,
        null -> { /* no-op */ }
    }
}

private fun priorityLabel(priority: TaskPriority): String = when (priority) {
    TaskPriority.None -> "No priority"
    TaskPriority.Low -> "Low priority"
    TaskPriority.Medium -> "Medium priority"
    TaskPriority.High -> "High priority"
    TaskPriority.Urgent -> "Urgent"
}

private fun dueDateLabel(option: DueDateOption): String = when (option) {
    DueDateOption.None -> "Добавить дату"
    DueDateOption.Today -> "Сегодня"
    DueDateOption.Tomorrow -> "Завтра"
    is DueDateOption.Custom -> option.label
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Composable
private fun TaskCreateContentPreview(
    state: TaskCreateUiState = TaskCreateUiState(),
) {
    TaskCreateContent(
        state = state,
        snackbarHost = { SnackbarHost(hostState = remember { SnackbarHostState() }) },
        onIntent = { },
        onBack = { },
    )
}

@Preview
@Composable
private fun TaskCreateContentDefaultPreview() = TaskCreateContentPreview(
    state = TaskCreateUiState(
        draft = TaskDraft(
            title = "",
            description = "",
            priority = TaskPriority.None,
            dueDate = DueDateOption.None,
        ),
        isSaveEnabled = false,
        isDirty = false,
        isSaving = false,
    ),
)

@Preview
@Composable
private fun TaskCreateContentErrorPreview() = TaskCreateContentPreview(
    state = TaskCreateUiState(
        draft = TaskDraft(
            title = "Buy groceries",
            priority = TaskPriority.Medium,
        ),
        isSaveEnabled = true,
        error = "Failed to save",
        isDirty = true,
        isSaving = false,
    ),
)

@Preview
@Composable
private fun TaskCreateContentSavingPreview() = TaskCreateContentPreview(
    state = TaskCreateUiState(
        draft = TaskDraft(
            title = "Buy groceries",
            priority = TaskPriority.High,
            dueDate = DueDateOption.Today,
        ),
        isSaveEnabled = false,
        isDirty = true,
        isSaving = true,
    ),
)
