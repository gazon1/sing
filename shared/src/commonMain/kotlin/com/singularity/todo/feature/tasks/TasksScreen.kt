package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.TaskPriority
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.tasks.components.BulkActionBar
import com.singularity.todo.feature.tasks.components.FilterChipsRow
import com.singularity.todo.feature.tasks.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.components.TaskCard
import com.singularity.todo.feature.tasks.components.TaskCardActions
import com.singularity.todo.core.ui.TestTags
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    entry: TasksScreenEntry = TasksScreenEntry.FromToday,
    onNavigateToTask: (String) -> Unit,
    onNavigateToCreateTask: () -> Unit,
    viewModel: TasksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    var aiSheetTask by remember { mutableStateOf<Task?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tasks") }) },
        // The FAB is provided by [AndroidShell] at the chrome level and adapts
        // per-tab. Don't render a second one here — see ADR 2026-09-05.
        bottomBar = {
            val currentState = state
            if (currentState is TasksUiState.Content && currentState.selectedIds.isNotEmpty()) {
                BulkActionBar(
                    selectedCount = currentState.selectedIds.size,
                    onComplete = viewModel::bulkCompleteSelected,
                    onDelete = viewModel::bulkDeleteSelected,
                    onCancel = viewModel::exitSelectionMode,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            FilterChipsRow(selected = filter, onSelect = viewModel::setFilter)
            TasksContent(
                state = state,
                onNavigateToTask = onNavigateToTask,
                onToggle = viewModel::toggle,
                onPin = viewModel::togglePin,
                onDelete = viewModel::delete,
                onAiClick = { aiSheetTask = it },
                onToggleSelection = viewModel::toggleSelection,
                onToggleExpand = viewModel::toggleExpand,
            )
        }
    }

    aiSheetTask?.let { task ->
        TaskAiBottomSheet(
            task = task,
            onAction = { action ->
                aiSheetTask = null
                viewModel.runAiAction(task, action)
            },
            onDismiss = { aiSheetTask = null },
        )
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag("tasks_notification_host"),
    )
}

@Composable
private fun TasksContent(
    state: TasksUiState,
    onNavigateToTask: (String) -> Unit,
    onToggle: (TaskId) -> Unit,
    onPin: (TaskId) -> Unit,
    onDelete: (TaskId) -> Unit,
    onAiClick: (Task) -> Unit,
    onToggleSelection: (TaskId) -> Unit,
    onToggleExpand: (TaskId) -> Unit,
) {
    when (state) {
        is TasksUiState.Loading -> LoadingIndicator()
        is TasksUiState.Empty -> EmptyState(title = "No tasks for ${state.filter.javaClass.simpleName}")
        is TasksUiState.Content -> TaskList(
            taskGroups = state.taskGroups,
            selectedIds = state.selectedIds,
            onNavigateToTask = onNavigateToTask,
            onToggle = onToggle,
            onPin = onPin,
            onDelete = onDelete,
            onAiClick = onAiClick,
            onToggleSelection = onToggleSelection,
            onToggleExpand = onToggleExpand,
        )
        is TasksUiState.Error -> EmptyState(title = "Error: ${state.message}")
    }
}

@Composable
private fun TaskList(
    taskGroups: List<TaskGroup>,
    selectedIds: Set<TaskId>,
    onNavigateToTask: (String) -> Unit,
    onToggle: (TaskId) -> Unit,
    onPin: (TaskId) -> Unit,
    onDelete: (TaskId) -> Unit,
    onAiClick: (Task) -> Unit,
    onToggleSelection: (TaskId) -> Unit,
    onToggleExpand: (TaskId) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.TASKS_LIST),
    ) {
        items(taskGroups, key = { group -> group.key }) { group ->
            when (group) {
                is TaskGroup.TopLevel -> {
                    TaskCard(
                        task = group.parent,
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                onToggleSelection(group.parent.id)
                            } else {
                                onNavigateToTask(group.parent.id.value)
                            }
                        },
                        onLongClick = { onToggleSelection(group.parent.id) },
                        actions = TaskCardActions { action ->
                            when (action) {
                                TaskCardActions.Action.Toggle -> onToggle(group.parent.id)
                                TaskCardActions.Action.Delete -> onDelete(group.parent.id)
                                TaskCardActions.Action.Ai -> onAiClick(group.parent)
                                TaskCardActions.Action.Pin -> onPin(group.parent.id)
                            }
                        },
                        trailing = {
                            if (group.children.isNotEmpty()) {
                                SubtaskExpandIcon(
                                    isExpanded = group.isExpanded,
                                    childCount = group.children.size,
                                    onToggle = { onToggleExpand(group.parent.id) },
                                )
                            }
                        },
                    )
                    // Render children when expanded
                    if (group.isExpanded) {
                        group.children.forEach { child ->
                            TaskCard(
                                task = child,
                                onClick = { onNavigateToTask(child.id.value) },
                                onLongClick = {},
                                actions = TaskCardActions { action ->
                                    when (action) {
                                        TaskCardActions.Action.Toggle -> onToggle(child.id)
                                        TaskCardActions.Action.Delete -> onDelete(child.id)
                                        TaskCardActions.Action.Ai -> onAiClick(child)
                                        TaskCardActions.Action.Pin -> onPin(child.id)
                                    }
                                },
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                    }
                }
                is TaskGroup.Child -> {
                    TaskCard(
                        task = group.task,
                        onClick = { onNavigateToTask(group.task.id.value) },
                        onLongClick = {},
                        actions = TaskCardActions { action ->
                            when (action) {
                                TaskCardActions.Action.Toggle -> onToggle(group.task.id)
                                TaskCardActions.Action.Delete -> onDelete(group.task.id)
                                TaskCardActions.Action.Ai -> onAiClick(group.task)
                                TaskCardActions.Action.Pin -> onPin(group.task.id)
                            }
                        },
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }
        }
    }
}

/** Expand/collapse icon with child count shown on parent task cards. */
@Composable
private fun SubtaskExpandIcon(
    isExpanded: Boolean,
    childCount: Int,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Text(
            text = "$childCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private val TaskGroup.key: String get() = when (this) {
    is TaskGroup.TopLevel -> parent.id.value
    is TaskGroup.Child -> task.id.value
}

private fun TasksUiEvent.toNotification(): Notification = when (this) {
    is TasksUiEvent.AiResult -> Notification.Text(title = "AI Result", text = text)
    is TasksUiEvent.Error -> Notification.Error(message)
    TasksUiEvent.NavigateBack -> Notification.NavigateBack
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TasksScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    TasksContent(
        state = TasksUiState.Content(
            filter = TaskFilter.Today,
            taskGroups = listOf(
                TaskGroup.TopLevel(
                    parent = PreviewSamples.task("t1", "Buy groceries", TaskPriority.High),
                    children = listOf(
                        PreviewSamples.task("t1a", "Buy milk", TaskPriority.Medium),
                        PreviewSamples.task("t1b", "Buy eggs", TaskPriority.Medium),
                    ),
                    isExpanded = false,
                ),
                TaskGroup.TopLevel(
                    parent = PreviewSamples.task("t2", "Read documentation", TaskPriority.Low, completed = true),
                    children = emptyList(),
                    isExpanded = false,
                ),
            ),
        ),
        onNavigateToTask = {},
        onToggle = {},
        onPin = {},
        onDelete = {},
        onAiClick = {},
        onToggleSelection = {},
        onToggleExpand = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TasksScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    TasksContent(
        state = TasksUiState.Empty(filter = TaskFilter.Today),
        onNavigateToTask = {},
        onToggle = {},
        onPin = {},
        onDelete = {},
        onAiClick = {},
        onToggleSelection = {},
        onToggleExpand = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TasksScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    TasksContent(
        state = TasksUiState.Content(
            filter = TaskFilter.Upcoming,
            taskGroups = listOf(
                TaskGroup.TopLevel(
                    parent = PreviewSamples.task("t1", "Finish project", TaskPriority.Urgent),
                    children = emptyList(),
                    isExpanded = false,
                ),
            ),
        ),
        onNavigateToTask = {},
        onToggle = {},
        onPin = {},
        onDelete = {},
        onAiClick = {},
        onToggleSelection = {},
        onToggleExpand = {},
    )
}
