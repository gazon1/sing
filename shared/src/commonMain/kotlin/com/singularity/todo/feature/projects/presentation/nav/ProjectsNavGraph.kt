package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.ProjectsRoute
import com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectsScreen

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
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block.
 */
@Composable
expect fun ProjectsNavGraph(
    start: ProjectsRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<ProjectsRoute>? = null,
)

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Projects
 * nested graph — the map from each [ProjectsRoute] variant to the screen that renders it.
 *
 * Extracted to commonMain because both platform implementations (Android, JVM) have
 * identical `entryProvider {}` bodies. Duplicating exhaustive `when`-over-sealed is
 * the dangerous kind: adding a variant to one file and forgetting the other compiles
 * cleanly in both and silently breaks at runtime.
 */
@Composable
internal fun projectsScreenEntryProvider() = entryProvider {
    entry<ProjectsRoute.List> { ProjectsScreen() }
    entry<ProjectsRoute.Editor> { ProjectEditorScreen(it.projectId) }
    entry<ProjectsRoute.Detail> { ProjectDetailScreen(it.projectId) }
}

/**
 * Returns a lambda that provides a [NavEntry] for each [ProjectsRoute] route type.
 *
 * Used by the outer app's [entryProvider] when it needs to delegate a
 * [AppDestination.ProjectsGraph] entry to the inner nested graph.
 */
@Composable
expect fun projectsEntryProvider(): (ProjectsRoute) -> NavEntry<ProjectsRoute>
