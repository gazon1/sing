package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.presentation.components.detail.LinkedBacklinksCard
import com.singularity.todo.feature.tasks.presentation.components.detail.LogbookSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailAttachmentsSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailChecklistSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailRecurrenceSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailTagsSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorMenuItem
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator

@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionSignature")
@Composable
fun TaskDetailContent(coordinator: TaskDetailCoordinator, modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val navigator = LocalTasksNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAiSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(coordinator.events) {
        coordinator.events.collect { event ->
            if (event is TaskDetailUiEvent.Saved) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    NotificationHost(
        events = coordinator.events,
        mapper = { event: TaskDetailUiEvent ->
            when (event) {
                is TaskDetailUiEvent.Saved -> Notification.None

                is TaskDetailUiEvent.Error -> Notification.Error(event.message)

                is TaskDetailUiEvent.UndoDelete -> Notification.Undo(
                    title = "Task deleted",
                    actionLabel = "Undo",
                    onAction = { coordinator.onIntent(TaskDetailIntent.Domain.Restore) },
                )

                TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
            }
        },
        onNavigateBack = { navigator.back() },
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                TaskDetailUiState.Loading -> LoadingState()

                is TaskDetailUiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { coordinator.retry() },
                )

                is TaskDetailUiState.Loaded -> {
                    val ui = s.ui

                    TaskEditorContent(
                        taskId = ui.task.id.value,
                        titleDraft = ui.titleDraft,
                        onTitleChange = { coordinator.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
                        isCompleted = ui.task.isCompleted,
                        onCheckToggle = { coordinator.onIntent(TaskDetailIntent.Domain.ToggleComplete) },
                        descriptionDraft = ui.descriptionDraft,
                        onDescriptionChange = { coordinator.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) },
                        priority = ui.task.priority,
                        onPrioritySelect = { coordinator.onIntent(TaskDetailIntent.Domain.SetPriority(it)) },
                        onPriorityClear = {
                            coordinator.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.None))
                        },
                        dueDate = ui.task.dueDate,
                        dueTime = ui.task.dueTime,
                        onDueDateSelect = { coordinator.onIntent(TaskDetailIntent.Domain.SetDueDate(it)) },
                        onDueDateClear = {
                            coordinator.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
                            coordinator.onIntent(TaskDetailIntent.Domain.SetDueTime(null))
                        },
                        onDueTimeSelect = { coordinator.onIntent(TaskDetailIntent.Domain.SetDueTime(it)) },
                        showDueDate = true,
                        onPriorityClick = null,
                        onDueDateClick = null,
                        startDate = null,
                        startTime = null,
                        startDateCallbacks = null,
                        project = null,
                        projectCallbacks = null,
                        tags = ui.tags.map { it.id },
                        tagsCallbacks = null,
                        recurrence = ui.task.recurrence,
                        recurrenceCallbacks = null,
                        isPinned = ui.task.isPinned,
                        pinCallbacks = null,
                        dependsOn = ui.dependsOn,
                        availableTasks = ui.availableTasks,
                        extraSections = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TaskDetailTagsSection(
                                    tags = ui.tags,
                                    onDeleteTag = { coordinator.onIntent(TaskDetailIntent.Domain.RemoveTag(it)) },
                                )
                                if (ui.task.recurrence != null) {
                                    TaskDetailRecurrenceSection(
                                        recurrence = ui.task.recurrence,
                                    )
                                }
                                TaskDetailChecklistSection(
                                    checklist = ui.checklist,
                                    onToggle = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(it))
                                    },
                                    onDelete = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.DeleteChecklistItem(it))
                                    },
                                )
                                if (ui.attachments.isNotEmpty()) {
                                    TaskDetailAttachmentsSection(
                                        attachments = ui.attachments,
                                    )
                                }
                                if (ui.linkedNotes.isNotEmpty() || ui.linkedTasks.isNotEmpty()) {
                                    LinkedBacklinksCard(
                                        linkedNotes = ui.linkedNotes,
                                        linkedTasks = ui.linkedTasks,
                                        onOpenNote = { navigator.openNote(it) },
                                        onOpenTask = { navigator.openDetail(it) },
                                    )
                                }
                                LogbookSection(
                                    entries = ui.logbookEntries,
                                    onOpenNote = { navigator.openNote(it) },
                                    onAddNote = { taskId -> navigator.openCreateNote(taskId) },
                                    currentTaskId = ui.task.id,
                                )
                            }
                        },
                        onSetDependencies = { coordinator.onIntent(TaskDetailIntent.Domain.SetDependencies(it)) },
                        bottomBar = null,
                        menuItems = buildDetailMenuItems(isTrashed = ui.task.isTrashed) { intent ->
                            coordinator.onIntent(intent)
                        },
                        onBack = { navigator.back() },
                        onAiClick = { showAiSheet = true },
                    )

                    if (showAiSheet) {
                        TaskAiBottomSheet(
                            task = ui.task,
                            onAction = { action ->
                                coordinator.onIntent(TaskDetailIntent.Domain.RunAiAction(action))
                                showAiSheet = false
                            },
                            onDismiss = { showAiSheet = false },
                        )
                    }
                }
            }
        }
    }
}

// ─── Menu items ─────────────────────────────────────────────────────────────

private fun buildDetailMenuItems(
    isTrashed: Boolean,
    onIntent: (TaskDetailIntent.Domain) -> Unit,
): List<TaskEditorMenuItem> = if (isTrashed) {
    listOf(
        TaskEditorMenuItem(
            label = "Восстановить",
            onClick = { onIntent(TaskDetailIntent.Domain.Unarchive) },
        ),
    )
} else {
    listOf(
        TaskEditorMenuItem(
            label = "Архивировать",
            onClick = { onIntent(TaskDetailIntent.Domain.Archive) },
        ),
        TaskEditorMenuItem(
            label = "Удалить",
            onClick = { onIntent(TaskDetailIntent.Domain.Delete) },
        ),
    )
}

// ─── Loading / Error ─────────────────────────────────────────────────────────

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
