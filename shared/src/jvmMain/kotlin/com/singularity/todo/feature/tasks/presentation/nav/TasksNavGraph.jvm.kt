package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.tasks.presentation.screen.TaskCreateScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskDetailViewScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskListScreen
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * JVM Desktop implementation of [TasksNavGraph].
 *
 * On desktop there is no system back gesture — [TasksBackHandler] is a no-op.
 * Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator] is used on JVM desktop — the JVM
 * does not have the ComponentActivity-based ViewModelStore scoping issue
 * that Android has. Each NavDisplay entry on desktop already has proper per-entry
 * ViewModel scoping, and there is no process-death lifecycle.
 *
 * **Serialization**: [SavedStateConfiguration] is initialized with a [SerializersModule]
 * that registers all [TasksRoute] subtypes. [NavBackStackSerializer] uses
 * [PolymorphicSerializer] for [NavKey], which needs to
 * know about every concrete subtype — including nested ones (e.g. [TasksRoute.Inbox] via
 * [TasksRoute.List]). [subclassesOfSealed] automatically discovers all subtypes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Composable
actual fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
) {
    // Use the SavedStateConfiguration DSL (invoke {} block) to set serializersModule.
    // This is the correct API: SavedStateConfiguration { serializersModule = ... }
    val savedStateConfig = remember {
        SavedStateConfiguration {
            serializersModule = SerializersModule {
                // Register TasksRoute and all its nested subtypes at the NavKey level.
                // TasksRoute.List → Inbox, Today, ByProject
                // TasksRoute.Detail, TasksRoute.Create
                polymorphic(NavKey::class) {
                    subclassesOfSealed<TasksRoute>()
                }
            }
        }
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
        // Desktop has no system back gesture — TasksBackHandler is a no-op here.

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            // No entryDecorators on JVM desktop.
            // Desktop has no process-death, so SaveableStateHolder is unnecessary.
            // Desktop has proper per-entry ViewModel scoping automatically.
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

@OptIn(ExperimentalSerializationApi::class)
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
