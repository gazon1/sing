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
import com.singularity.todo.feature.nav.TasksRoute
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.tasks.presentation.screen.TaskCreateScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskDetailScreen

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
 *
 * @param backStack Pre-created stack. When non-null the stack is NOT re-created
 *   on recomposition, which prevents nested navigation state from being lost when
 *   the parent [com.singularity.todo.feature.nav.Nav3State] triggers recomposition.
 *   When null (default), creates a new stack via [rememberInMemoryNavBackStack].
 */
@Composable
actual fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
    backStack: NavBackStack<TasksRoute>?,
) {
    val stack: NavBackStack<TasksRoute> = backStack ?: rememberInMemoryNavBackStack(start)

    val navigator = remember(stack, onExitGraph) {
        TasksNavigator(stack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalTasksNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<TasksRoute.Detail> { route -> TaskDetailScreen(route.taskId) }
                entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate) }
            },
        )
    }
}

@Composable
actual fun tasksEntryProvider(): (TasksRoute) -> NavEntry<TasksRoute> = entryProvider {
    entry<TasksRoute.Detail> { route -> TaskDetailScreen(route.taskId) }
    entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate) }
}
