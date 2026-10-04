package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack
import com.singularity.todo.feature.notes.presentation.screen.NoteEditorScreen
import com.singularity.todo.feature.notes.presentation.screen.NotePreviewScreen
import com.singularity.todo.feature.notes.presentation.screen.NotesListScreen

/**
 * JVM Desktop implementation of [NotesNavGraph].
 *
 * Uses an in-memory [NavBackStack] — no process death on Desktop, so
 * [SavedStateConfiguration] is dead code and was removed.
 *
 * On desktop there is no system back gesture — [NotesBackHandler] is a no-op.
 * Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [rememberViewModelStoreNavEntryDecorator] is used on JVM desktop.
 *
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block,
 *                  preventing nested navigation state from being lost on tab switches.
 */
@Composable
actual fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    start: NotesRoute,
    modifier: Modifier,
    backStack: NavBackStack<NotesRoute>?,
) {
    val stack: NavBackStack<NotesRoute> = backStack ?: rememberInMemoryNavBackStack(start)

    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit

    val navigator = remember(stack, onExitGraph) {
        NotesNavigator(stack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalNotesNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<NotesRoute.List> { NotesListScreen(it) }
                entry<NotesRoute.Preview> { route -> NotePreviewScreen(route) }
                entry<NotesRoute.Editor> { route -> NoteEditorScreen(route) }
            },
        )
    }
}
