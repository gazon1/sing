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
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.navSavedStateConfig
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
 * Persistence: uses [navSavedStateConfig] so the back stack survives process death.
 */
@Composable
actual fun TasksNavGraph(start: TasksRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    // remember { }, not rememberSaveable { }. SavedStateConfiguration is a schema
    // (which concrete NavKey subtypes exist), not a value to persist. It is constant
    // across process death — only the NavBackStack content is serialized.
    val savedStateConfig = remember {
        navSavedStateConfig(
            TasksRoute.Inbox.serializer(),
            TasksRoute.Today.serializer(),
            TasksRoute.ByProject.serializer(),
            TasksRoute.Detail.serializer(),
            TasksRoute.Create.serializer(),
            TasksRoute.Upcoming.serializer(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<TasksRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<TasksRoute>

    val navigator = remember(backStack, onExitGraph) {
        TasksNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalTasksNavigator provides navigator,
    ) {
        // Intercept system back at the start route to exit the nested graph.
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<TasksRoute.Detail> { route -> TaskDetailViewScreen(route.taskId) }
                entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate) }
            },
        )
    }
}

@Composable
actual fun tasksEntryProvider(): (TasksRoute) -> NavEntry<TasksRoute> = entryProvider {
    entry<TasksRoute.Detail> { route -> TaskDetailViewScreen(route.taskId) }
    entry<TasksRoute.Create> { route -> TaskCreateScreen(route.initialDueDate) }
}
