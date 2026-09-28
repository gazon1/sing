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
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.navSavedStateConfig
import com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectEditorScreen
import com.singularity.todo.feature.projects.presentation.screen.ProjectsScreen

/**
 * Android implementation of [ProjectsNavGraph].
 * Creates a nested [NavDisplay] with its own [NavBackStack] for the projects feature,
 * providing [LocalProjectsNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 *
 * Persistence: uses [navSavedStateConfig] so the back stack survives process death.
 */
@Composable
actual fun ProjectsNavGraph(start: ProjectsRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    // remember { }, not rememberSaveable { }. SavedStateConfiguration is a schema
    // (which concrete NavKey subtypes exist), not a value to persist.
    val savedStateConfig = remember {
        navSavedStateConfig(ProjectsRoute.serializer())
    }

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
actual fun projectsEntryProvider(): (ProjectsRoute) -> NavEntry<ProjectsRoute> = entryProvider {
    entry<ProjectsRoute.List> { ProjectsScreen() }
    entry<ProjectsRoute.Editor> { ProjectEditorScreen(it.projectId) }
    entry<ProjectsRoute.Detail> { ProjectDetailScreen(it.projectId) }
}
