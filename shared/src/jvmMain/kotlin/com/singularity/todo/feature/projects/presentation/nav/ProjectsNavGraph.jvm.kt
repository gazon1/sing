package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.projects.ProjectDetailScreen
import com.singularity.todo.feature.projects.ProjectEditorScreen
import com.singularity.todo.feature.projects.ProjectsScreen

/**
 * JVM Desktop implementation of [ProjectsNavGraph].
 *
 * On desktop there is no system back gesture — [ProjectsBackHandler] is a no-op.
 * Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator] is used on JVM desktop — the JVM
 * does not have the ComponentActivity-based ViewModelStore scoping issue
 * that Android has. Each NavDisplay entry on desktop already has proper
 * per-entry ViewModel scoping, and there is no process-death lifecycle.
 */
@Composable
actual fun ProjectsNavGraph(
    start: ProjectsRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
) {
    val savedStateConfig = remember { SavedStateConfiguration { } }
    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<ProjectsRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<ProjectsRoute>

    val navigator = remember(backStack, onExitGraph) {
        ProjectsNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalProjectsNavigator provides navigator,
    ) {
        // Desktop has no system back gesture — ProjectsBackHandler is a no-op here.

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            // No entryDecorators on JVM desktop.
            // Desktop has no process-death, so SaveableStateHolder is unnecessary.
            // Desktop has proper per-entry ViewModel scoping automatically.
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<ProjectsRoute.List> { ProjectsScreen() }
                entry<ProjectsRoute.Editor> { ProjectEditorScreen(it.projectId) }
                entry<ProjectsRoute.Detail> { ProjectDetailScreen(it.projectId) }
            },
        )
    }
}

@Composable
actual fun projectsEntryProvider(): (ProjectsRoute) -> NavEntry<ProjectsRoute> {
    return entryProvider {
        entry<ProjectsRoute.List> { ProjectsScreen() }
        entry<ProjectsRoute.Editor> { ProjectEditorScreen(it.projectId) }
        entry<ProjectsRoute.Detail> { ProjectDetailScreen(it.projectId) }
    }
}
