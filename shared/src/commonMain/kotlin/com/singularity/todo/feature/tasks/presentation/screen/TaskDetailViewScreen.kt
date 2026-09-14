package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailViewContent
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Task detail screen (View mode) for the tasks nested navigation graph.
 * Reads [LocalTasksNavigator] for all navigation actions — no callbacks needed.
 */
@Composable
fun TaskDetailViewScreen(
    taskId: TaskId,
) {
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

    when (val s = state) {
        TaskDetailUiState.Loading -> { /* TODO */ }
        is TaskDetailUiState.Error -> { /* TODO */ }
        is TaskDetailUiState.Loaded -> {
            TaskDetailViewContent(
                ui = s.ui,
                onIntent = { intent ->
                    when (intent) {
                        is TaskDetailIntent.Domain -> vm.onIntent(intent)
                        else -> { /* routing intents now use callbacks */ }
                    }
                },
                onBack = { navigator.back() },
                onNavigateToProject = { projectId -> navigator.openProject(projectId) },
            )
        }
    }
}
