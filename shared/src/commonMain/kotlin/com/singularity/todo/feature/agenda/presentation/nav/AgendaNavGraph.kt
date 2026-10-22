package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppNavBackStack

/**
 * The nested navigation graph of the agenda feature.
 *
 * Provides its own [NavBackStack] with [AgendaStartRoute] keys, independent of the
 * outer app back stack. Screens inside use [LocalAgendaNavigator] to navigate
 * without manual callbacks.
 *
 * ## The context menu
 *
 * The menu is a platform gesture — a bottom sheet on Android, a popup on Desktop —
 * so the host arrives as a parameter and the graph stays in common code. What
 * lives here is the list of sections that show it.
 *
 * Every task section shows the menu. Android used to leave the three "problem"
 * sections — blocked, stagnant, overdue — without it while Desktop showed it
 * everywhere; the divergence was accidental, and the parity test
 * `AgendaContextMenuParityTest` now holds both platforms to the same list.
 *
 * @param start the initial [AgendaStartRoute] to show.
 * @param onExitGraph called when the nested graph should close. The [AppDestination]
 *   argument, if non-null, is the destination to navigate to in the outer graph.
 * @param contextMenuHost the platform's task context menu.
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun AgendaNavGraph(
    start: AgendaStartRoute,
    onExitGraph: (AppDestination?) -> Unit,
    contextMenuHost: AgendaContextMenuHost,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<AgendaStartRoute>? = null,
) {
    val stack: NavBackStack<AgendaStartRoute> = backStack ?: rememberAppNavBackStack(start)
    val navigator = remember(stack, onExitGraph) {
        AgendaNavigator(
            backStack = stack,
            onExitGraph = onExitGraph,
        )
    }
    CompositionLocalProvider(
        LocalAgendaNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { onExitGraph(null) },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = agendaScreenEntryProvider(contextMenuHost),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Agenda
 * nested graph — the map from each [AgendaStartRoute] variant to the screen that
 * renders it.
 *
 * @param contextMenuHost the platform's task context menu, passed to every task
 *   section.
 */
@Composable
internal fun agendaScreenEntryProvider(contextMenuHost: AgendaContextMenuHost) = entryProvider {
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
    entry<AgendaStartRoute.SavedAgendaResults> { route -> AgendaNavContent(route = route) }
    entry<AgendaStartRoute.SavedAgendaEdit> { route -> AgendaNavContent(route = route) }
    entry<AgendaStartRoute.SavedAgendaCreate> { route -> AgendaNavContent(route = route) }
    entry<AgendaStartRoute.Blocked> { AgendaNavContent(route = it, contextMenuHost = contextMenuHost) }
    entry<AgendaStartRoute.Stagnant> { AgendaNavContent(route = it, contextMenuHost = contextMenuHost) }
    entry<AgendaStartRoute.Overdue> { AgendaNavContent(route = it, contextMenuHost = contextMenuHost) }
}
