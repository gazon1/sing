package com.singularity.todo.feature.projects.presentation.nav

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
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.projects.ProjectDetailScreen
import com.singularity.todo.feature.projects.ProjectEditorScreen
import com.singularity.todo.feature.projects.ProjectsScreen

/**
 * Android implementation of [ProjectsNavGraph].
 * Creates a nested [NavDisplay] with its own [NavBackStack] for the projects feature,
 * providing [LocalProjectsNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
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
        // Intercept system back at the start route to exit the nested graph.
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(
                // Koin bug workaround: without this, koinViewModel { parametersOf(projectId) }
                // would resolve LocalViewModelStoreOwner to ComponentActivity instead of NavEntry,
                // causing ProjectDetailViewModel(X) → back → ProjectDetailViewModel(Y) to show X's state.
                rememberViewModelStoreNavEntryDecorator(),
            ),
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
