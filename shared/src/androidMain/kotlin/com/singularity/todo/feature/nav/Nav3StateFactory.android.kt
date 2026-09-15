package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Android implementation of [rememberNav3State].
 *
 * Uses [navSavedStateConfig] to construct a [SavedStateConfiguration] that registers every
 * [AppDestination] concrete subtype at the [NavKey] polymorphic level. This enables
 * `rememberNavBackStack` to serialize the back stack across process death and configuration
 * changes via the standard Compose saved-state mechanism.
 *
 * The configuration is a no-op on Desktop (where it would be dead code), which is why
 * the JVM implementation uses [rememberInMemoryNavBackStack] instead — see
 * [Nav3StateFactory.jvm.kt].
 *
 * @see navSavedStateConfig
 */
@Composable
actual fun rememberNav3State(): Nav3State {
    val startRoute: NavKey = AppDestination.Today
    val topLevelRoutes: Set<NavKey> =
        DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()

    val topLevelRouteState: MutableState<NavKey> = remember(startRoute) {
        mutableStateOf(startRoute)
    }

    val savedStateConfig = remember {
        navSavedStateConfig(
            // Top-level tab + menu data objects
            AppDestination.Inbox.serializer(),
            AppDestination.Today.serializer(),
            AppDestination.Plans.serializer(),
            AppDestination.Pomodoro.serializer(),
            AppDestination.Statistics.serializer(),
            AppDestination.Notes.serializer(),
            AppDestination.AiChat.serializer(),
            AppDestination.Search.serializer(),
            AppDestination.Archive.serializer(),
            AppDestination.Settings.serializer(),
            AppDestination.AiUsage.serializer(),
            AppDestination.ProfileSwitcher.serializer(),
            // Sub-route data classes (push-on-top of a top-level destination)
            AppDestination.TasksGraph.serializer(),
            AppDestination.TasksByProject.serializer(),
            AppDestination.TaskDetail.serializer(),
            AppDestination.TaskDetailCreate.serializer(),
            AppDestination.ProjectEditor.serializer(),
            AppDestination.ProjectDetail.serializer(),
            AppDestination.ProjectsGraph.serializer(),
            AppDestination.NotesGraph.serializer(),
        )
    }

    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(savedStateConfig, key)
    }

    return remember(startRoute, topLevelRoutes) {
        Nav3State(startRoute, topLevelRouteState, backStacks)
    }
}
