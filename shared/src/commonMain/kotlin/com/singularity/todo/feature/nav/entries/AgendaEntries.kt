package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.agenda.presentation.nav.AgendaContextMenuHost
import com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavStacks
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.stableContentKey

/**
 * The app-level entries the agenda feature owns.
 *
 * @param nav the outer navigation callbacks.
 * @param stacks the stacks the platform hoists — the agenda tabs share one stack
 *   each, so an inner push survives a tab round-trip.
 * @param contextMenuHost the platform's task context menu.
 */
fun EntryProviderScope<AppDestination>.agendaEntries(
    nav: NavCallbacks,
    stacks: AppNavStacks,
    contextMenuHost: AgendaContextMenuHost,
) {
    entry<AppDestination.AgendaGraph>(clazzContentKey = { it.stableContentKey() }) { route ->
        AgendaNavGraph(
            start = route.start,
            onExitGraph = nav.graphExit,
            contextMenuHost = contextMenuHost,
            backStack = stacks.agenda[route.start],
        )
    }
}
