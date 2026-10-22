package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.ProjectsRoute
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppNavBackStack
import com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectsScreen

/**
 * The nested navigation graph of the projects feature.
 *
 * Provides its own [NavBackStack] with [ProjectsRoute] keys, independent of the
 * outer app back stack. Screens inside use [LocalProjectsNavigator] to navigate
 * without manual callbacks.
 *
 * @param start the initial [ProjectsRoute] to show (e.g. [ProjectsRoute.List]).
 * @param onExitGraph called when the nested graph should close. The [AppDestination]
 *   argument, if non-null, is the destination to navigate to in the outer graph.
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun ProjectsNavGraph(
    start: ProjectsRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<ProjectsRoute>? = null,
) {
    val stack: NavBackStack<ProjectsRoute> = backStack ?: rememberAppNavBackStack(start)
    val navigator = remember(stack, onExitGraph) {
        ProjectsNavigator(stack, onExitGraph)
    }
    CompositionLocalProvider(
        LocalProjectsNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = projectsScreenEntryProvider(),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Projects
 * nested graph — the map from each [ProjectsRoute] variant to the screen that renders it.
 */
@Composable
internal fun projectsScreenEntryProvider() = entryProvider {
    entry<ProjectsRoute.List> { ProjectsScreen() }
    entry<ProjectsRoute.Editor> { ProjectEditorScreen(it.projectId) }
    entry<ProjectsRoute.Detail> { ProjectDetailScreen(it.projectId) }
}
