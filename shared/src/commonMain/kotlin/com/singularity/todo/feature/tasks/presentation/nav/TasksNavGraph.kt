package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.TasksRoute

/**
 * Creates a nested navigation graph for the tasks feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [TasksRoute] keys,
 * independent of the outer app back stack. Screens inside use [LocalTasksNavigator]
 * to navigate without needing manual callbacks.
 *
 * ## Architecture
 *
 * - [LocalTasksNavigator], [TasksNavigator], [TasksRoute] — commonMain (platform-agnostic)
 * - This function — platform-specific implementations
 *   - Android: includes [rememberViewModelStoreNavEntryDecorator] for per-entry VM scoping
 *     and [BackHandler] for system back gesture
 *   - JVM: no decorators needed (desktop has no ComponentActivity scoping issue)
 *
 * @param start The initial [TasksRoute] to show (e.g. [TasksRoute.Create]).
 * @param onExitGraph Called when the nested graph should close.
 *                    The [AppDestination] argument, if non-null, is the destination
 *                    to navigate to in the outer graph (e.g. [AppDestination.ProjectDetail]).
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block,
 *                  preventing nested navigation state from being lost on tab switches.
 */
@Composable
expect fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<TasksRoute>? = null,
)

/**
 * Returns a lambda that provides a [NavEntry] for each [TasksRoute] route type.
 *
 * Used by the outer app's [entryProvider] when it needs to delegate a
 * [AppDestination.TasksGraph] entry to the inner nested graph.
 */
@Composable
expect fun tasksEntryProvider(): (TasksRoute) -> NavEntry<TasksRoute>
