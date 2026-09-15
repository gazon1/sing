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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailViewContent
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.nav.PreviewTasksNavigator
import com.singularity.todo.feature.tasks.presentation.nav.TasksPreviewWrapper
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
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

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            TaskDetailUiState.Loading -> LoadingState()
            is TaskDetailUiState.Error -> ErrorState(
                message = s.message,
                onRetry = { vm.retry() },
            )
            is TaskDetailUiState.Loaded -> TaskDetailViewContent(
                ui = s.ui,
                events = vm.events,
                recentlyDeleted = vm.recentlyDeleted,
                onIntent = { intent ->
                    if (intent is TaskDetailIntent.Domain) vm.onIntent(intent)
                },
                navigator = navigator,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
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

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview
@Composable
private fun TaskDetailViewScreenPreview() = PreviewThemed(useSurface = false) {
    TasksPreviewWrapper {
        TaskDetailViewContent(
            ui = PreviewSamples.taskDetailUi(),
            events = MutableSharedFlow(),
            recentlyDeleted = kotlinx.coroutines.flow.emptyFlow(),
            onIntent = { },
            navigator = PreviewTasksNavigator(),
        )
    }
}

@Preview
@Composable
private fun TaskDetailViewScreenHighPriorityPreview() = PreviewThemed(useSurface = false) {
    TasksPreviewWrapper {
        TaskDetailViewContent(
            ui = PreviewSamples.taskDetailUi(
                task = PreviewSamples.task(
                    title = "URGENT: Deploy to production",
                    priority = TaskPriority.Urgent,
                ),
            ),
            events = MutableSharedFlow(),
            recentlyDeleted = kotlinx.coroutines.flow.emptyFlow(),
            onIntent = { },
            navigator = PreviewTasksNavigator(),
        )
    }
}
