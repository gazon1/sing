package com.singularity.todo.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.tasks.components.BulkActionBar
import com.singularity.todo.feature.tasks.components.FilterChipsRow
import com.singularity.todo.feature.tasks.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.components.TaskCard
import com.singularity.todo.feature.tasks.components.TaskCardActions
import org.koin.compose.koinInject

sealed interface TasksScreenEntry {
    data object FromToday : TasksScreenEntry
    data object FromInbox : TasksScreenEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onNavigateToTask: (String) -> Unit,
    onNavigateToCreateTask: () -> Unit,
) {
    val viewModel: TasksViewModel = koinInject()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    var dialogText by remember { mutableStateOf<String?>(null) }
    var aiSheetTask by remember { mutableStateOf<Task?>(null) }

    CollectEvents(viewModel.events) { event ->
        if (event is UiEvent.ShowDialog || event is UiEvent.ShowError) {
            dialogText = event.toDialogText()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tasks") }) },
        floatingActionButton = {
            val currentState = state
            val hasSelection = currentState is TasksUiState.Content && currentState.selectedIds.isNotEmpty()
            if (!hasSelection) {
                FloatingActionButton(onClick = onNavigateToCreateTask) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Task")
                }
            }
        },
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

    ResultDialog(title = "AI Result", text = dialogText, onDismiss = { dialogText = null })
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
) {
    when (state) {
        is TasksUiState.Loading -> LoadingIndicator()
        is TasksUiState.Empty -> EmptyState(title = "No tasks for ${state.filter.javaClass.simpleName}")
        is TasksUiState.Content -> TaskList(
            tasks = state.tasks,
            onNavigateToTask = onNavigateToTask,
            onToggle = onToggle,
            onPin = onPin,
            onDelete = onDelete,
            onAiClick = onAiClick,
            selectedIds = state.selectedIds,
            onToggleSelection = onToggleSelection,
        )
        is TasksUiState.Error -> EmptyState(title = "Error: ${state.message}")
    }
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    onNavigateToTask: (String) -> Unit,
    onToggle: (TaskId) -> Unit,
    onPin: (TaskId) -> Unit,
    onDelete: (TaskId) -> Unit,
    onAiClick: (Task) -> Unit,
    selectedIds: Set<TaskId>,
    onToggleSelection: (TaskId) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tasks, key = { it.id.value }) { task ->
            TaskCard(
                task = task,
                onClick = {
                    if (selectedIds.isNotEmpty()) {
                        onToggleSelection(task.id)
                    } else {
                        onNavigateToTask(task.id.value)
                    }
                },
                onLongClick = { onToggleSelection(task.id) },
                actions = TaskCardActions { action ->
                    when (action) {
                        TaskCardActions.Action.Toggle -> onToggle(task.id)
                        TaskCardActions.Action.Delete -> onDelete(task.id)
                        TaskCardActions.Action.Ai -> onAiClick(task)
                        TaskCardActions.Action.Pin -> onPin(task.id)
                    }
                },
            )
        }
    }
}

private fun UiEvent.toDialogText(): String = when (this) {
    is UiEvent.ShowDialog -> text
    is UiEvent.ShowError -> message
    UiEvent.NavigateBack -> ""
}
