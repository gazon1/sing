package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavStacks
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.nav.toNotesRoute
import com.singularity.todo.feature.notes.presentation.nav.NotesNavGraph

/**
 * The app-level entries the notes feature owns.
 *
 * @param nav the outer navigation callbacks.
 * @param stacks the stacks the platform hoists.
 */
fun EntryProviderScope<AppDestination>.notesEntries(nav: NavCallbacks, stacks: AppNavStacks) {
    entry<AppDestination.Notes> {
        NotesNavGraph(
            navCallbacks = nav,
            start = NotesRoute.List,
            backStack = stacks.notes,
        )
    }

    entry<AppDestination.NotesGraph> { route ->
        NotesNavGraph(
            navCallbacks = nav,
            start = route.start.toNotesRoute(),
        )
    }
}
