package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.ui.menu.ContextMenuHost
import com.singularity.todo.core.ui.menu.ContextMenuOpenState
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.tasks.presentation.contextmenu.TaskMenuActions
import com.singularity.todo.feature.tasks.presentation.contextmenu.buildTaskContextMenu
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

/**
 * JVM Desktop implementation of [AgendaNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * SavedStateConfiguration is not needed. No system back gesture on desktop —
 * handled via the outer app's toolbar.
 */
@Composable
actual fun AgendaNavGraph(start: AgendaStartRoute, onExitGraph: (AppDestination?) -> Unit, modifier: Modifier) {
    val backStack: NavBackStack<AgendaStartRoute> = rememberInMemoryNavBackStack(start)

    val navigator = remember(backStack, onExitGraph) {
        AgendaNavigator(
            backStack = backStack,
            onExitGraph = onExitGraph,
        )
    }

    // Desktop context menu host — renders the actual ContextMenuHost.
    // Uses platform desktop colors (TaskListColors) via the ContextMenuHost implementation.
    // The [onIntent] parameter is passed through AgendaScreen → AgendaContent so that
    // menu actions (pin, delete, expand, AI) can dispatch domain intents.
    val desktopContextMenuHost: @Composable (TaskUi, androidx.compose.ui.unit.DpOffset, () -> Unit, (AgendaIntent) -> Unit) -> Unit =
        { taskUi, offset, onDismiss, onIntent ->
            val menuActions = remember(taskUi) {
                TaskMenuActions(
                    onTogglePin = { onIntent(AgendaIntent.TaskPinClicked(taskUi.id)) },
                    onToggleComplete = { onIntent(AgendaIntent.TaskCheckClicked(taskUi.id)) },
                    onDelete = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
                    onToggleExpand = { onIntent(AgendaIntent.TaskExpandClicked(taskUi.id)) },
                    onAiAction = { /* AI actions deferred — requires AgendaDeps extension */ },
                    onDismiss = onDismiss,
                )
            }
            val menuEntries = remember(taskUi) {
                buildTaskContextMenu(
                    taskUi = taskUi,
                    hasAiContext = taskUi.domainTask != null,
                    actions = menuActions,
                )
            }
            ContextMenuHost(
                openState = ContextMenuOpenState(offset),
                onDismiss = onDismiss,
                entries = menuEntries,
            )
        }

    CompositionLocalProvider(
        LocalAgendaNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { onExitGraph(null) },
            entryProvider = entryProvider {
                entry<AgendaStartRoute.Inbox> {
                    AgendaNavContent(route = it, desktopContextMenuHost = desktopContextMenuHost)
                }
                entry<AgendaStartRoute.Today> {
                    AgendaNavContent(route = it, desktopContextMenuHost = desktopContextMenuHost)
                }
                entry<AgendaStartRoute.Upcoming> {
                    AgendaNavContent(route = it, desktopContextMenuHost = desktopContextMenuHost)
                }
                entry<AgendaStartRoute.Project> { r ->
                    AgendaNavContent(route = r, desktopContextMenuHost = desktopContextMenuHost)
                }
                entry<AgendaStartRoute.Tag> { r ->
                    AgendaNavContent(route = r, desktopContextMenuHost = desktopContextMenuHost)
                }
                entry<AgendaStartRoute.SavedAgendaList> {
                    AgendaNavContent(route = it)
                }
                entry<AgendaStartRoute.SavedAgendaEdit> { r ->
                    AgendaNavContent(route = r)
                }
                entry<AgendaStartRoute.SavedAgendaCreate> {
                    AgendaNavContent(route = it)
                }
            },
        )
    }
}

@Composable
actual fun agendaEntryProvider(): (AgendaStartRoute) -> NavEntry<AgendaStartRoute> = entryProvider {
    entry<AgendaStartRoute.Inbox> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.Today> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.Upcoming> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.Project> { r -> AgendaNavContent(route = r) }
    entry<AgendaStartRoute.Tag> { r -> AgendaNavContent(route = r) }
    entry<AgendaStartRoute.SavedAgendaList> { AgendaNavContent(route = it) }
    entry<AgendaStartRoute.SavedAgendaEdit> { r -> AgendaNavContent(route = r) }
    entry<AgendaStartRoute.SavedAgendaCreate> { AgendaNavContent(route = it) }
}
