package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.ProjectsRoute

/**
 * Creates a nested navigation graph for the projects feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [ProjectsRoute] keys,
 * independent of the outer app back stack. Screens inside use [LocalProjectsNavigator]
 * to navigate without needing manual callbacks.
 *
 * ## Architecture
 *
 * - [LocalProjectsNavigator], [ProjectsNavigator], [ProjectsRoute] — commonMain (platform-agnostic)
 * - This function — platform-specific implementations
 *   - Android: includes [rememberViewModelStoreNavEntryDecorator] for per-entry VM scoping
 *     and [ProjectsBackHandler] for system back gesture
 *   - JVM: no decorators needed (desktop has no ComponentActivity scoping issue)
 *
 * @param start The initial [ProjectsRoute] to show (e.g. [ProjectsRoute.List]).
 * @param onExitGraph Called when the nested graph should close.
 *                    The [AppDestination] argument, if non-null, is the destination
 *                    to navigate to in the outer graph (e.g. [AppDestination.TasksByProject]).
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 */
@Composable
expect fun ProjectsNavGraph(
    start: ProjectsRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * Returns a lambda that provides a [NavEntry] for each [ProjectsRoute] route type.
 *
 * Used by the outer app's [entryProvider] when it needs to delegate a
 * [AppDestination.ProjectsGraph] entry to the inner nested graph.
 */
@Composable
expect fun projectsEntryProvider(): (ProjectsRoute) -> NavEntry<ProjectsRoute>
