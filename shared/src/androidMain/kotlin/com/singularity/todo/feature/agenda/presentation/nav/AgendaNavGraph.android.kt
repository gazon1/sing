package com.singularity.todo.feature.agenda.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.appNavSavedStateConfig
import com.singularity.todo.feature.tasks.presentation.contextmenu.TaskContextMenuSheet
import com.singularity.todo.feature.tasks.presentation.model.TaskUi

/**
 * Android implementation of [AgendaNavGraph].
 *
 * Uses [appNavSavedStateConfig] so the back stack survives process death.
 * Uses [BackHandler] for the system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin scoping bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 *
 * The context menu on touch is a long-press bottom sheet; the JVM graph wires its
 * right-click popup into the same slot instead.
 */
@Composable
actual fun AgendaNavGraph(start: AgendaStartRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    val savedStateConfig = appNavSavedStateConfig

    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<AgendaStartRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<AgendaStartRoute>

    val navigator = remember(backStack, onExitGraph) {
        AgendaNavigator(
            backStack = backStack,
            onExitGraph = onExitGraph,
        )
    }

    // Long-press context menu. testTagsAsResourceId is re-asserted because the
    // ModalBottomSheet hosts its own window, which does not inherit the flag set
    // at the app root (same pattern as the shell's MenuBottomSheet).
    val contextMenuHost: @Composable (
        TaskUi,
        androidx.compose.ui.unit.DpOffset,
        () -> Unit,
        (AgendaIntent) -> Unit,
    ) -> Unit = { taskUi, _, onDismiss, onIntent ->
        TaskContextMenuSheet(
            task = taskUi,
            onOpen = { onIntent(AgendaIntent.TaskClicked(taskUi.id)) },
            onToggleComplete = { onIntent(AgendaIntent.TaskCheckClicked(taskUi.id)) },
            onTogglePin = { onIntent(AgendaIntent.TaskPinClicked(taskUi.id)) },
            onArchive = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
            onDismiss = onDismiss,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
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
                entry<AgendaStartRoute.Inbox> { AgendaNavContent(route = it, contextMenuHost = contextMenuHost) }
                entry<AgendaStartRoute.Today> { AgendaNavContent(route = it, contextMenuHost = contextMenuHost) }
                entry<AgendaStartRoute.Upcoming> { AgendaNavContent(route = it, contextMenuHost = contextMenuHost) }
                entry<AgendaStartRoute.Project> { route ->
                    AgendaNavContent(
                        route = route,
                        contextMenuHost = contextMenuHost,
                    )
                }
                entry<AgendaStartRoute.Tag> { route ->
                    AgendaNavContent(
                        route = route,
                        contextMenuHost = contextMenuHost,
                    )
                }
                entry<AgendaStartRoute.SavedAgendaList> { AgendaNavContent(route = it) }
                entry<AgendaStartRoute.SavedAgendaEdit> { route -> AgendaNavContent(route = route) }
                entry<AgendaStartRoute.SavedAgendaCreate> { AgendaNavContent(route = it) }
            },
        )
    }
}

@Composable
actual fun agendaEntryProvider(): (AgendaStartRoute) -> NavEntry<AgendaStartRoute> = entryProvider {
    entry<AgendaStartRoute.Inbox> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.Today> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.Upcoming> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.Project> { route -> AgendaNavContent(route = route) }
    entry<AgendaStartRoute.Tag> { route -> AgendaNavContent(route = route) }
    entry<AgendaStartRoute.SavedAgendaList> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.SavedAgendaEdit> { route -> AgendaNavContent(route = route) }
    entry<AgendaStartRoute.SavedAgendaCreate> { AgendaNavContent(route = it) }
}
