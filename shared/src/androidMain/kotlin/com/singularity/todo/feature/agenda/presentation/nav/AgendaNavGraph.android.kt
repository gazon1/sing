package com.singularity.todo.feature.agenda.presentation.nav

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
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.presentation.screen.AgendaScreen
import com.singularity.todo.feature.agenda.presentation.screen.SavedAgendaEditScreen
import com.singularity.todo.feature.agenda.presentation.screen.SavedAgendaListScreen
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.navSavedStateConfig

/**
 * Android implementation of [AgendaNavGraph].
 *
 * Uses [navSavedStateConfig] so the back stack survives process death.
 * Uses [BackHandler] for the system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin scoping bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 */
@Composable
actual fun AgendaNavGraph(start: AgendaStartRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    val savedStateConfig = remember {
        navSavedStateConfig(
            AgendaStartRoute.Inbox.serializer(),
            AgendaStartRoute.Today.serializer(),
            AgendaStartRoute.Upcoming.serializer(),
            AgendaStartRoute.Project.serializer(),
            AgendaStartRoute.Tag.serializer(),
            AgendaStartRoute.SavedAgendaList.serializer(),
            AgendaStartRoute.SavedAgendaEdit.serializer(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<AgendaStartRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<AgendaStartRoute>

    val navigator = remember(backStack, onExitGraph) {
        AgendaNavigator(
            backStack = backStack,
            onExitGraph = onExitGraph,
        )
    }

    CompositionLocalProvider(
        LocalAgendaNavigator provides navigator,
    ) {
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { onExitGraph(null) },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<AgendaStartRoute.Inbox> { AgendaScreen(definition = AgendaPresets.Inbox) }
                entry<AgendaStartRoute.Today> { AgendaScreen(definition = AgendaPresets.Today) }
                entry<AgendaStartRoute.Upcoming> { AgendaScreen(definition = AgendaPresets.Upcoming) }
                entry<AgendaStartRoute.Project> { route ->
                    AgendaScreen(
                        definition = AgendaPresets.byProject(route.id),
                    )
                }
                entry<AgendaStartRoute.Tag> { route -> AgendaScreen(definition = AgendaPresets.byTag(route.id)) }
                entry<AgendaStartRoute.SavedAgendaList> { SavedAgendaListScreen() }
                entry<AgendaStartRoute.SavedAgendaEdit> { route -> SavedAgendaEditScreen(viewId = SavedAgendaViewId.fromString(route.viewId)) }
            },
        )
    }
}

@Composable
actual fun agendaEntryProvider(): (AgendaStartRoute) -> NavEntry<AgendaStartRoute> = entryProvider {
    entry<AgendaStartRoute.Inbox> { AgendaScreen(definition = AgendaPresets.Inbox) }
    entry<AgendaStartRoute.Today> { AgendaScreen(definition = AgendaPresets.Today) }
    entry<AgendaStartRoute.Upcoming> { AgendaScreen(definition = AgendaPresets.Upcoming) }
    entry<AgendaStartRoute.Project> { route -> AgendaScreen(definition = AgendaPresets.byProject(route.id)) }
    entry<AgendaStartRoute.Tag> { route -> AgendaScreen(definition = AgendaPresets.byTag(route.id)) }
    entry<AgendaStartRoute.SavedAgendaList> { SavedAgendaListScreen() }
    entry<AgendaStartRoute.SavedAgendaEdit> { route -> SavedAgendaEditScreen(viewId = SavedAgendaViewId.fromString(route.viewId)) }
}
