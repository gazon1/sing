package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.ui.menu.ContextMenuHost
import com.singularity.todo.core.ui.menu.ContextMenuOpenState
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.tasks.presentation.contextmenu.TaskMenuActions
import com.singularity.todo.feature.tasks.presentation.contextmenu.buildTaskContextMenu
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import org.koin.compose.koinInject

/**
 * JVM Desktop implementation of [AgendaNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * SavedStateConfiguration is not needed. No system back gesture on desktop —
 * handled via the outer app's toolbar.
 *
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block,
 *                  preventing nested navigation state from being lost on tab switches.
 */
@Composable
actual fun AgendaNavGraph(
    start: AgendaStartRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier,
    backStack: NavBackStack<AgendaStartRoute>?,
) {
    val stack: NavBackStack<AgendaStartRoute> = backStack ?: rememberInMemoryNavBackStack(start)

    val navigator = remember(stack, onExitGraph) {
        AgendaNavigator(
            backStack = stack,
            onExitGraph = onExitGraph,
        )
    }

    // Desktop context menu host — renders the actual ContextMenuHost.
    // Uses platform desktop colors via the ContextMenuHost implementation.
    // The [onIntent] parameter is passed through AgendaScreen → AgendaContent so that
    // menu actions (pin, delete, expand, AI) can dispatch domain intents.
    val contextMenuHost: @Composable (
        TaskUi,
        androidx.compose.ui.unit.DpOffset,
        () -> Unit,
        (AgendaIntent) -> Unit,
    ) -> Unit =
        { taskUi, offset, onDismiss, onIntent ->
            // Share is a platform action, so the port is resolved here rather than
            // pushed through the ViewModel. Keyed on the task because the shared text
            // is the task's own title and description.
            val sharePort: SharePort = koinInject()
            val menuActions = remember(taskUi) {
                TaskMenuActions(
                    onTogglePin = { onIntent(AgendaIntent.TaskPinClicked(taskUi.id)) },
                    onToggleComplete = { onIntent(AgendaIntent.TaskCheckClicked(taskUi.id)) },
                    onDelete = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
                    onToggleExpand = { onIntent(AgendaIntent.TaskExpandClicked(taskUi.id)) },
                    onAiAction = { /* AI actions deferred — requires AgendaDeps extension */ },
                    // Archive and Delete are the same write here (softDelete); both
                    // items stay because the list and the archive screen each name
                    // that write in the user's own vocabulary.
                    onArchive = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
                    onShare = { sharePort.shareText(taskUi.title, taskUi.title) },
                    onDismiss = onDismiss,
                )
            }
            val menuEntries = remember(taskUi) {
                buildTaskContextMenu(
                    taskUi = taskUi,
                    // domainTask removed — any visible task with a title is AI-context eligible
                    hasAiContext = taskUi.title.isNotBlank(),
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
            backStack = stack,
            modifier = modifier,
            onBack = { onExitGraph(null) },
            entryProvider = entryProvider {
                entry<AgendaStartRoute.Inbox> {
                    AgendaNavContent(route = it, contextMenuHost = contextMenuHost)
                }
                entry<AgendaStartRoute.Today> {
                    AgendaNavContent(route = it, contextMenuHost = contextMenuHost)
                }
                entry<AgendaStartRoute.Upcoming> {
                    AgendaNavContent(route = it, contextMenuHost = contextMenuHost)
                }
                entry<AgendaStartRoute.Project> { r ->
                    AgendaNavContent(route = r, contextMenuHost = contextMenuHost)
                }
                entry<AgendaStartRoute.Tag> { r ->
                    AgendaNavContent(route = r, contextMenuHost = contextMenuHost)
                }
                entry<AgendaStartRoute.SavedAgendaList> {
                    AgendaNavContent(route = it)
                }
                entry<AgendaStartRoute.SavedAgendaResults> { r ->
                    AgendaNavContent(route = r)
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
    entry<AgendaStartRoute.SavedAgendaResults> { r -> AgendaNavContent(route = r) }
    entry<AgendaStartRoute.SavedAgendaEdit> { r -> AgendaNavContent(route = r) }
    entry<AgendaStartRoute.SavedAgendaCreate> { AgendaNavContent(route = it) }
}
