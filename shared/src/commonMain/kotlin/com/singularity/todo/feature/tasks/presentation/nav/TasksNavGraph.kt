package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.TasksRoute
import com.singularity.todo.feature.nav.rememberAppNavBackStack
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.tasks.presentation.screen.TaskCreateScreen
import com.singularity.todo.feature.tasks.presentation.screen.TaskDetailScreen

/**
 * The nested navigation graph of the tasks feature.
 *
 * Provides its own [NavBackStack] with [TasksRoute] keys, independent of the outer
 * app back stack. Screens inside use [LocalTasksNavigator] to navigate without
 * manual callbacks.
 *
 * ## Why this file is platform-agnostic
 *
 * It used to be an `expect` with an Android and a JVM implementation that differed
 * in four ways: the stack's storage, the system back gesture, the ViewModel-store
 * decorator, and whether a hoisted stack was honoured. Three of those are now a
 * seam — [rememberAppNavBackStack], [SystemBackHandler] — and the fourth turned
 * out to be the same on both platforms (see below). What was left were two copies
 * of the same graph.
 *
 * ## The ViewModel store
 *
 * [rememberViewModelStoreNavEntryDecorator] gives every entry its own ViewModel
 * store. Without it an entry resolves the store of whatever is above it, so two
 * entries of the same graph share one store — and Koin keys a ViewModel by its
 * class alone, so the second entry silently reuses the first one's ViewModel,
 * with the first one's parameters. Measured on Desktop by
 * `NestedEntryViewModelScopeTest`: the Desktop graphs did not have the decorator,
 * and they did share the store.
 *
 * @param start the initial [TasksRoute] to show (e.g. [TasksRoute.Create]).
 * @param onExitGraph called when the nested graph should close. The [AppDestination]
 *   argument, if non-null, is the destination to navigate to in the outer graph.
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<TasksRoute>? = null,
) {
    val stack: NavBackStack<TasksRoute> = backStack ?: rememberAppNavBackStack(start)
    val navigator = remember(stack, onExitGraph) {
        TasksNavigator(stack, onExitGraph)
    }
    CompositionLocalProvider(
        LocalTasksNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = tasksScreenEntryProvider(),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Tasks
 * nested graph — the map from each [TasksRoute] variant to the screen that renders it.
 */
@Composable
internal fun tasksScreenEntryProvider() = entryProvider {
    entry<TasksRoute.Detail>(clazzContentKey = { it.stableContentKey() }) { route -> TaskDetailScreen(route) }
    entry<TasksRoute.Create>(clazzContentKey = { it.stableContentKey() }) { route ->
        TaskCreateScreen(route.initialDueDate, route.draftKey)
    }
}
