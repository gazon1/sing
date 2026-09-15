package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.notes.presentation.screen.NoteEditorScreen
import com.singularity.todo.feature.notes.presentation.screen.NotePreviewScreen
import com.singularity.todo.feature.notes.presentation.screen.NotesListScreen

/**
 * JVM Desktop implementation of [NotesNavGraph].
 *
 * On desktop there is no system back gesture — [NotesBackHandler] is a no-op.
 * Back navigation is handled via the outer app's toolbar / window controls.
 *
 * No [androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator] is used
 * on JVM desktop — the JVM does not have the ComponentActivity-based ViewModelStore scoping
 * issue that Android has. Each NavDisplay entry on desktop already has proper per-entry
 * ViewModel scoping, and there is no process-death lifecycle.
 */
@Composable
actual fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier,
) {
    val savedStateConfig = remember { SavedStateConfiguration { } }
    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<NotesRoute> = rememberNavBackStack(savedStateConfig, *arrayOf(NotesRoute.List))
        as NavBackStack<NotesRoute>

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
        // Desktop has no system back gesture — BackHandler is a no-op on JVM.

        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            // No entryDecorators on JVM desktop.
            // Desktop has no process-death, so SaveableStateHolder is unnecessary.
            // Desktop has proper per-entry ViewModel scoping automatically.
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<NotesRoute.List> { NotesListScreen(it) }
                entry<NotesRoute.Preview> { route -> NotePreviewScreen(route) }
                entry<NotesRoute.Editor> { route -> NoteEditorScreen(route) }
            },
        )
    }
}
