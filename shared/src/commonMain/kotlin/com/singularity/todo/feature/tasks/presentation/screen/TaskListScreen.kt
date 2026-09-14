package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TasksUiEvent
import com.singularity.todo.feature.tasks.presentation.components.BulkActionBar
import com.singularity.todo.feature.tasks.presentation.components.FilterChipsRow
import com.singularity.todo.feature.tasks.presentation.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.presentation.components.list.EmptyState
import com.singularity.todo.feature.tasks.presentation.components.list.SwipeableTaskRow
import com.singularity.todo.feature.tasks.presentation.components.list.TaskFilterChips
import com.singularity.todo.feature.tasks.presentation.components.list.TaskListHeader
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowFlat
import com.singularity.todo.feature.tasks.presentation.model.TaskListFilter
import com.singularity.todo.feature.tasks.presentation.model.TaskListStats
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.nav.TasksRoute
import com.singularity.todo.feature.tasks.presentation.nav.toDomainFilter
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListShapes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.viewmodel.TasksViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * Task list screen — the main entry point for Inbox / Today / ByProject lists.
 *
 * Uses [LocalTasksNavigator] for all navigation — no callback parameters needed.
 * The [TasksRoute.List] route drives the initial domain filter.
 *
 * @param route The list route this screen was opened with — determines the initial
 *              domain-level filter applied to [TasksViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    route: TasksRoute.List,
) {
    val navigator = LocalTasksNavigator.current
    val vm: TasksViewModel = koinInject()

    val state by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val statusFilter by vm.statusFilter.collectAsStateWithLifecycle()

    // Apply the route's domain filter on first composition
    val filterFromRoute: com.singularity.todo.feature.tasks.domain.model.TaskFilter = when (route) {
        is com.singularity.todo.feature.tasks.presentation.nav.TasksRoute.Inbox ->
            com.singularity.todo.feature.tasks.domain.model.TaskFilter.Inbox
        is com.singularity.todo.feature.tasks.presentation.nav.TasksRoute.Today ->
            com.singularity.todo.feature.tasks.domain.model.TaskFilter.Today
        is com.singularity.todo.feature.tasks.presentation.nav.TasksRoute.ByProject ->
            com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByProject(route.projectId!!)
    }
    LaunchedEffect(filterFromRoute) {
        vm.setFilter(filterFromRoute)
    }

    // AI bottom sheet state
    var aiSheetTask by remember { mutableStateOf<Task?>(null) }

    // Undo snackbar state
    var lastDeleted by remember { mutableStateOf<TaskUi?>(null) }
    var snackbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Pull-to-refresh
    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Derived stats from current tasks
    val currentTasks = (state as? com.singularity.todo.feature.tasks.domain.model.TasksUiState.Content)?.tasks ?: emptyList()
    val stats by remember(currentTasks) { derivedStateOf { TaskListStats.from(currentTasks) } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TaskListColors.Background,
        contentColor = TaskListColors.TextPrimary,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navigator.openCreate() },
                containerColor = TaskListColors.Accent,
                contentColor = TaskListColors.OnAccent,
                shape = TaskListShapes.FabRadius,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Новая задача") },
            )
        },
        bottomBar = {
            val currentState = state
            if (currentState is com.singularity.todo.feature.tasks.domain.model.TasksUiState.Content && currentState.selectedIds.isNotEmpty()) {
                BulkActionBar(
                    selectedCount = currentState.selectedIds.size,
                    onComplete = vm::bulkCompleteSelected,
                    onDelete = vm::bulkDeleteSelected,
                    onCancel = vm::exitSelectionMode,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Header
            val headerTitle = when (route) {
                is TasksRoute.Inbox -> "Inbox"
                is TasksRoute.Today -> "Сегодня"
                is TasksRoute.ByProject -> "Проект" // project name injected via route if needed
            }
            TaskListHeader(
                title = headerTitle,
                taskCount = stats.active,
                onCalendarClick = { /* TODO: calendar picker */ },
                onMoreClick = { /* TODO: more actions */ },
                subtitle = null, // TODO: compute date subtitle
            )

            // Domain-level filter chips (Today / Upcoming / Someday / Inbox / Pinned)
            FilterChipsRow(
                selected = filter,
                onSelect = vm::setFilter,
            )

            // Status-level filter chips (All / Active / Completed)
            TaskFilterChips(
                selected = statusFilter,
                onSelect = vm::setStatusFilter,
                counts = mapOf(
                    TaskListFilter.ALL to stats.total,
                    TaskListFilter.ACTIVE to stats.active,
                    TaskListFilter.COMPLETED to stats.completed,
                ),
            )

            // List content
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        vm.refresh()
                        delay(1200.milliseconds)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.TopCenter),
                        isRefreshing = isRefreshing,
                        state = pullToRefreshState,
                        containerColor = TaskListColors.Surface,
                        color = TaskListColors.Accent,
                    )
                },
            ) {
                when (val s = state) {
                    is com.singularity.todo.feature.tasks.domain.model.TasksUiState.Loading -> {
                        // TODO: LoadingIndicator — use shared component
                    }
                    is com.singularity.todo.feature.tasks.domain.model.TasksUiState.Empty -> {
                        EmptyState(
                            title = "Задач пока нет",
                            description = "Нажмите «Новая задача» внизу, чтобы добавить первую.",
                            icon = Icons.Default.CheckCircle,
                        )
                    }
                    is com.singularity.todo.feature.tasks.domain.model.TasksUiState.Error -> {
                        EmptyState(
                            title = "Ошибка: ${s.message}",
                            description = "Попробуйте обновить список.",
                            icon = Icons.Default.CheckCircle,
                        )
                    }
                    is com.singularity.todo.feature.tasks.domain.model.TasksUiState.Content -> {
                        val tasks = s.tasks
                        val contentState = s
                        if (tasks.isEmpty()) {
                            val isFilterActive = statusFilter != TaskListFilter.ALL
                            EmptyState(
                                title = if (isFilterActive) "Здесь пусто" else "Задач нет",
                                description = if (isFilterActive) {
                                    "В выбранном фильтре задач нет. Попробуйте «Все»."
                                } else {
                                    "Нажмите «Новая задача» внизу."
                                },
                                icon = if (isFilterActive) Icons.Default.CheckCircle else Icons.Default.Add,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = TaskListSpacing.None,
                                    vertical = TaskListSpacing.Sm,
                                ),
                                verticalArrangement = Arrangement.Top,
                            ) {
                                items(tasks, key = { it.id.value }) { task ->
                                    SwipeableTaskRow(
                                        onDelete = {
                                            vm.delete(task.id)
                                            lastDeleted = task
                                            snackbarJob?.cancel()
                                            snackbarJob = scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Задача удалена",
                                                    actionLabel = "Отменить",
                                                    withDismissAction = true,
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    vm.restore(task.id)
                                                }
                                                lastDeleted = null
                                            }
                                        },
                                        backgroundShape = RoundedCornerShape(0.dp),
                                    ) {
                                        TaskRowFlat(
                                            task = task,
                                            indentLevel = task.indentLevel,
                                            onToggleCompleted = { vm.toggle(task.id) },
                                            onClick = {
                                                if (contentState.selectedIds.isNotEmpty()) {
                                                    vm.toggleSelection(task.id)
                                                } else {
                                                    navigator.openDetail(task.id)
                                                }
                                            },
                                            showDivider = task != tasks.last(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // AI bottom sheet
    aiSheetTask?.let { task ->
        TaskAiBottomSheet(
            task = task,
            onAction = {
                aiSheetTask = null
                vm.runAiAction(task, it)
            },
            onDismiss = { aiSheetTask = null },
        )
    }

    // Notification host for AI results / errors
    NotificationHost(
        events = vm.events,
        mapper = { event: TasksUiEvent ->
            when (event) {
                is TasksUiEvent.AiResult -> Notification.Text(title = "AI", text = event.message)
                is TasksUiEvent.Error -> Notification.Error(message = event.message)
                TasksUiEvent.NavigateBack -> Notification.None
            }
        },
    )
}
