package com.singularity.todo.feature.tasks.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.TasksRoute
import com.singularity.todo.feature.nav.navSavedStateConfig
import com.singularity.todo.feature.nav.rememberNavBackStackTyped
import com.singularity.todo.feature.tasks.presentation.screen.TaskCreateScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskDetailViewScreen

/**
 * Android implementation of [TasksNavGraph].
 * Creates a nested [NavDisplay] with its own [NavBackStack] for the tasks feature,
 * providing [LocalTasksNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 *
 * Persistence: uses [navSavedStateConfig()] so the back stack survives process death.
 *
 * @param backStack Ignored on Android. Android always creates its own stack via
 *                  [rememberNavBackStack] with [navSavedStateConfig] for process-death survival.
 */
@Composable
actual fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") backStack: NavBackStack<TasksRoute>?,
) {
    val stack: NavBackStack<TasksRoute> = rememberNavBackStackTyped(navSavedStateConfig(), start)

    val navigator = remember(stack, onExitGraph) {
        TasksNavigator(stack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalTasksNavigator provides navigator,
    ) {
        // Intercept system back at the start route to exit the nested graph.
        BackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<TasksRoute.Detail> { route -> TaskDetailViewScreen(route.taskId) }
                entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate, route.sectionPrefillKey) }
            },
        )
    }
}

@Composable
actual fun tasksEntryProvider(): (TasksRoute) -> NavEntry<TasksRoute> = entryProvider {
    entry<TasksRoute.Detail> { route -> TaskDetailViewScreen(route.taskId) }
    entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate, route.sectionPrefillKey) }
}
