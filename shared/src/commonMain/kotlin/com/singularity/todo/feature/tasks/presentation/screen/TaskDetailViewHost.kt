package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailMode
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Thin host for TaskDetail View mode.
 * Owns the VM lifecycle and dispatches to TaskDetailViewContent.
 */
@Composable
fun TaskDetailViewHost(
    mode: TaskDetailMode.View,
    onBack: () -> Unit,
    onNavigateToProject: (ProjectId) -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
) {
    val vm: TaskDetailViewModel = koinViewModel { parametersOf(mode.taskId) }

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
        onNavigateBack = onBack,
    )

    when (val s = state) {
        TaskDetailUiState.Loading -> { /* TODO */ }
        is TaskDetailUiState.Error -> { /* TODO */ }
        is TaskDetailUiState.Loaded -> {
            TaskDetailViewContent(
                ui = s.ui,
                onIntent = { intent ->
                    when (intent) {
                        is TaskDetailIntent.NavigateToProject -> onNavigateToProject(intent.id)
                        is TaskDetailIntent.NavigateToTask -> onNavigateToTask(intent.id)
                        is TaskDetailIntent.Domain -> vm.onIntent(intent)
                        else -> { /* routing intents handled in content */ }
                    }
                },
                onBack = onBack,
            )
        }
    }
}
