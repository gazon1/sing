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
 */
@Composable
actual fun NotesNavGraph(navCallbacks: NavCallbacks, start: NotesRoute, modifier: Modifier) {
    val backStack: NavBackStack<NotesRoute> = rememberInMemoryNavBackStack(start)

    val onExitGraph: (AppDestination?) -> Unit = { dest ->
        if (dest != null) {
            navCallbacks.navigate(dest)
        } else {
            navCallbacks.goBack()
        }
    }

    val navigator = remember(backStack, onExitGraph) {
        NotesNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalNotesNavigator provides navigator,
    ) {
        NavDisplay(
            backStack = backStack,
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
