package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.feature.tasks.presentation.components.upcoming.DaySwitcherRow
import com.singularity.todo.feature.tasks.presentation.components.upcoming.UpcomingTopBar
import com.singularity.todo.feature.tasks.presentation.components.upcoming.UpcomingTaskRow
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.nav.TasksRoute
import com.singularity.todo.feature.tasks.presentation.state.UpcomingIntent
import com.singularity.todo.feature.tasks.presentation.state.UpcomingUiState
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.viewmodel.UpcomingViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Upcoming screen — shows tasks scheduled for a user-selected date.
 *
 * Accessed as the 3rd tab in the bottom navigation bar, or via
 * [LocalTasksNavigator.openUpcoming] from the calendar icon in the Today screen.
 *
 * No FAB: task creation goes through the shared [LocalTasksNavigator.openCreate]
 * entry point which is shared with all other task-list screens.
 */
@Composable
fun UpcomingScreen(route: TasksRoute.Upcoming) {
    val navigator = LocalTasksNavigator.current
    val vm: UpcomingViewModel = koinViewModel { parametersOf(route.date) }
    val state by vm.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = state,
            label = "upcomingState",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            when (s) {
                UpcomingUiState.Loading -> {
                    LoadingIndicator()
                }

                is UpcomingUiState.Content -> {
                    UpcomingContent(
                        state = s,
                        onIntent = vm::onIntent,
                        onBack = navigator::back,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingContent(
    state: UpcomingUiState.Content,
    onIntent: (UpcomingIntent) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        UpcomingTopBar(
            selectedDate = state.selectedDate,
            onBack = onBack,
        )

        DaySwitcherRow(
            windowStart = state.windowStart,
            selectedDate = state.selectedDate,
            onIntent = onIntent,
        )

        HorizontalDivider(color = TaskListColors.Divider)

        if (state.tasks.isEmpty()) {
            EmptyState(
                title = "No tasks",
                subtitle = "Nothing is scheduled for this day.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = state.tasks,
                    key = { it.id.value },
                ) { task ->
                    UpcomingTaskRow(
                        task = task,
                        onToggle = { onIntent(UpcomingIntent.ToggleTask(task.id)) },
                        onClick = { /* TODO: open task detail */ },
                    )
                }

                // FAB clearance — no FAB on this screen, but keeps consistent bottom padding
                item {
                    Spacer(Modifier.height(TaskListSpacing.Xxl + TaskListSpacing.Xxl))
                }
            }
        }
    }
}
