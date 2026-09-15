package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.tasks.presentation.screen.TaskCreateScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskDetailViewScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskListScreen

/**
 * JVM Desktop implementation of [TasksNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * [SavedStateConfiguration] is dead code and was removed.
 * [LocalSaveableStateRegistry] is always `null` on JVM Desktop (the
 * `savedstate-compose-desktop` artifact is a deliberate empty stub for
 * JetBrains/kotlin-wrappers interop; see `savedstate-compose-desktop` docs).
 *
 * On desktop there is no system back gesture — [TasksBackHandler] is a no-op.
 * Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator] is used on JVM desktop —
 * each NavDisplay entry already has proper per-entry ViewModel scoping.
 */
@Composable
actual fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
) {
    val backStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(start)

    val navigator = remember(backStack, onExitGraph) {
        TasksNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalTasksNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<TasksRoute.Inbox> { route -> TaskListScreen(route) }
                entry<TasksRoute.Today> { route -> TaskListScreen(route) }
                entry<TasksRoute.ByProject> { route -> TaskListScreen(route) }
                entry<TasksRoute.Detail> { route -> TaskDetailViewScreen(route.taskId) }
                entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate) }
            },
        )
    }
}

@Composable
actual fun tasksEntryProvider(): (TasksRoute) -> NavEntry<TasksRoute> {
    return entryProvider {
        entry<TasksRoute.Inbox> { route -> TaskListScreen(route) }
        entry<TasksRoute.Today> { route -> TaskListScreen(route) }
        entry<TasksRoute.ByProject> { route -> TaskListScreen(route) }
        entry<TasksRoute.Detail> { route -> TaskDetailViewScreen(route.taskId) }
        entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate) }
    }
}
