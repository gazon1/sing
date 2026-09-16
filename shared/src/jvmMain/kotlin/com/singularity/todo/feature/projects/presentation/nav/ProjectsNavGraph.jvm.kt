package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectsScreen

/**
 * JVM Desktop implementation of [ProjectsNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * [SavedStateConfiguration] is dead code and was removed.
 *
 * On desktop there is no system back gesture — [ProjectsBackHandler] is a no-op.
 * Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator] is used on JVM desktop.
 */
@Composable
actual fun ProjectsNavGraph(start: ProjectsRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    val backStack: NavBackStack<ProjectsRoute> = rememberInMemoryNavBackStack(start)

    val navigator = remember(backStack, onExitGraph) {
        ProjectsNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalProjectsNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
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
actual fun projectsEntryProvider(): (ProjectsRoute) -> NavEntry<ProjectsRoute> = entryProvider {
    entry<ProjectsRoute.List> { ProjectsScreen() }
    entry<ProjectsRoute.Editor> { ProjectEditorScreen(it.projectId) }
    entry<ProjectsRoute.Detail> { ProjectDetailScreen(it.projectId) }
}
