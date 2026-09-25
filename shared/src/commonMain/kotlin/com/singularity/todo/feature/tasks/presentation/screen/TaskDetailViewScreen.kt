package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.formatTimestampsRelative
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskAiAction
import com.singularity.todo.feature.tasks.presentation.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorMenuItem
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorModel
import com.singularity.todo.feature.tasks.presentation.components.detail.DateRowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.ToggleCallbacks
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock

/**
 * Task detail screen (View mode) for the tasks nested navigation graph.
 * Reads [LocalTasksNavigator] for all navigation actions — no callbacks needed.
 */
@Composable
fun TaskDetailViewScreen(taskId: TaskId) {
    val vm: TaskDetailViewModel = koinViewModel { parametersOf(taskId) }
    val navigator = LocalTasksNavigator.current

    val state by vm.state.collectAsStateWithLifecycle()

    NotificationHost(
        events = vm.events,
        mapper = { event: TaskDetailUiEvent ->
            when (event) {
                is TaskDetailUiEvent.Saved -> Notification.Text(event.message, null)
                is TaskDetailUiEvent.Error -> Notification.Error(event.message)
                is TaskDetailUiEvent.UndoDelete -> Notification.Text("Task deleted", null)
                TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
            }
        },
        onNavigateBack = { navigator.back() },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            TaskDetailUiState.Loading -> LoadingState()

            is TaskDetailUiState.Error -> ErrorState(
                message = s.message,
                onRetry = { vm.retry() },
            )

            is TaskDetailUiState.Loaded -> {
                val ui = s.ui
                var showAiSheet by remember { mutableStateOf(false) }
                val model = TaskEditorModel(
                    taskId = taskId,
                    titleDraft = ui.titleDraft,
                    descriptionDraft = ui.descriptionDraft,
                    priority = ui.task.priority,
                    dueDate = ui.task.dueDate,
                    dueTime = ui.task.dueTime,
                    startDate = ui.task.startDate,
                    startTime = ui.task.startTime,
                    project = ui.task.projectId,
                    tags = ui.tags.map { it.id },
                    checklist = ui.checklist,
                    attachments = ui.attachments,
                    recurrence = ui.task.recurrence,
                    isPinned = ui.task.isPinned,
                    dependsOn = ui.dependsOn,
                    availableTasks = ui.availableTasks,
                )

                val callbacks = TaskEditorCallbacks(
                    onBack = { navigator.back() },
                    onTitleChange = { vm.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
                    onCheckToggle = { vm.onIntent(TaskDetailIntent.Domain.ToggleComplete) },
                    onDescriptionChange = { vm.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) },
                    priority = RowCallbacks(
                        onChange = { vm.onIntent(TaskDetailIntent.Domain.SetPriority(it)) },
                        onClear = null,
                    ),
                    dueDate = DateRowCallbacks(
                        onChangeDate = { vm.onIntent(TaskDetailIntent.Domain.SetDueDate(it)) },
                        onChangeTime = { vm.onIntent(TaskDetailIntent.Domain.SetDueTime(it)) },
                        onClear = null,
                    ),
                    startDate = null,
                    project = null,
                    tags = null,
                    recurrence = null,
                    pin = ToggleCallbacks(
                        onToggle = { vm.onIntent(TaskDetailIntent.Domain.TogglePinned) },
                    ),
                    dependencies = RowCallbacks(
                        onChange = { vm.onIntent(TaskDetailIntent.Domain.SetDependencies(it)) },
                        onClear = null,
                    ),
                    checklist = null,
                    attachments = null,
                    bottomBar = null,
                    menuItems = listOf(
                        TaskEditorMenuItem(
                            label = "Архивировать",
                            onClick = { vm.onIntent(TaskDetailIntent.Domain.Archive) },
                        ),
                        TaskEditorMenuItem(
                            label = "Удалить",
                            onClick = { vm.onIntent(TaskDetailIntent.Domain.Delete) },
                        ),
                    ),
                    onAiClick = if (!showAiSheet) {{ showAiSheet = true }} else null,
                )

                TaskEditorContent(
                    model = model,
                    callbacks = callbacks,
                    isCompleted = ui.task.isCompleted,
                )

                if (showAiSheet) {
                    TaskAiBottomSheet(
                        task = ui.task,
                        onAction = { action ->
                            showAiSheet = false
                            vm.onIntent(TaskDetailIntent.Domain.RunAiAction(action))
                        },
                        onDismiss = { showAiSheet = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}
