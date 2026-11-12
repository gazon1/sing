package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAgendaRouteHolder
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.rememberAppNavBackStack
import com.singularity.todo.feature.nav.stableContentKey

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
    val navigator =
        remember(stack, onExitGraph) {
            AgendaNavigator(
                backStack = stack,
                onExitGraph = onExitGraph,
            )
        }

    // The route this graph is on, published to the shell above it. The shell draws
    // the button over the agenda, and it sits outside this graph: it can see the
    // graph as one destination, but not the route inside it. Without this, every
    // screen inside the agenda got the button of the tab the agenda opened with —
    // including the saved-view screens, which carry no shell button and draw
    // their own, and the shell's button was drawn on top of that one.
    val routeHolder = LocalAgendaRouteHolder.current
    val currentRoute = stack.lastOrNull() ?: start
    SideEffect { routeHolder?.publish(currentRoute) }
    DisposableEffect(routeHolder) {
        onDispose { routeHolder?.clear() }
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
internal fun agendaScreenEntryProvider(contextMenuHost: AgendaContextMenuHost) =
    entryProvider {
        entry<AgendaStartRoute.Inbox>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.Today>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.Upcoming>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.Project>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.Tag>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.SavedAgendaList>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route)
        }
        entry<AgendaStartRoute.SavedAgendaResults>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route)
        }
        entry<AgendaStartRoute.SavedAgendaEdit>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route)
        }
        entry<AgendaStartRoute.SavedAgendaCreate>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route)
        }
        entry<AgendaStartRoute.Blocked>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.Stagnant>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
        entry<AgendaStartRoute.Overdue>(clazzContentKey = { it.stableContentKey() }) { route ->
            AgendaNavContent(route = route, contextMenuHost = contextMenuHost)
        }
    }
