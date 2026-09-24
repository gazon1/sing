package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.formatTimestampsRelative
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorMenuItem
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
                TaskEditorContent(
                    titleDraft = ui.titleDraft,
                    onTitleChange = { vm.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
                    isCompleted = ui.task.isCompleted,
                    onCheckToggle = { vm.onIntent(TaskDetailIntent.Domain.ToggleComplete) },
                    descriptionDraft = ui.descriptionDraft,
                    onDescriptionChange = { vm.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) },
                    priority = ui.task.priority,
                    onPrioritySelect = { vm.onIntent(TaskDetailIntent.Domain.SetPriority(it)) },
                    onPriorityClear = null,
                    dueDate = ui.task.dueDate,
                    dueTime = ui.task.dueTime,
                    onDueDateSelect = { vm.onIntent(TaskDetailIntent.Domain.SetDueDate(it)) },
                    onDueDateClear = null,
                    onDueTimeSelect = { vm.onIntent(TaskDetailIntent.Domain.SetDueTime(it)) },
                    showDueDate = ui.task.dueDate != null,
                    dependsOn = ui.dependsOn,
                    availableTasks = ui.availableTasks,
                    onSetDependencies = { deps -> vm.onIntent(TaskDetailIntent.Domain.SetDependencies(deps)) },
                    extraSections = {
                        if (ui.checklist.isNotEmpty()) {
                            com.singularity.todo.feature.tasks.presentation.components.detail.TaskChecklistCard(
                                itemCount = ui.checklist.count { !it.isCompleted },
                                onClick = { },
                            )
                        }
                        ui.project?.let { project ->
                            com.singularity.todo.feature.tasks.presentation.components.detail.TaskAttributeCard(
                                icon = Icons.Filled.Folder,
                                label = project.name,
                                isActive = true,
                                onClick = { navigator.openProject(project.id) },
                            )
                        }
                        if (ui.tags.isNotEmpty()) {
                            com.singularity.todo.feature.tasks.presentation.components.detail.TaskAttributeCard(
                                icon = Icons.AutoMirrored.Filled.Label,
                                label = ui.tags.joinToString { it.name },
                                isActive = true,
                                onClick = { },
                            )
                        }
                        val ts = formatTimestampsRelative(ui.task.createdAt, ui.task.updatedAt, Clock.System.now())
                        Text(
                            text = "${ts.created} · ${ts.updated}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
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
                    onBack = { navigator.back() },
                )
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
        modifier = Modifier
            .fillMaxSize()
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

// ─── Previews ────────────────────────────────────────────────────────────────
// TaskDetailViewScreen is tested via integration tests (Nav3 + VM).
// Basic preview of the TaskEditorContent component is in TaskEditorContent.kt.
