package com.singularity.todo.feature.notes.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.nav.navSavedStateConfig
import com.singularity.todo.feature.nav.rememberNavBackStackTyped
import com.singularity.todo.feature.notes.presentation.screen.NoteEditorScreen
import com.singularity.todo.feature.notes.presentation.screen.NotePreviewScreen
import com.singularity.todo.feature.notes.presentation.screen.NotesListScreen

/**
 * Android implementation of [NotesNavGraph].
 * Creates a nested [NavDisplay] with its own [NavBackStack] for the notes feature,
 * providing [LocalNotesNavigator] to all descendant screens.
 *
 * Uses [BackHandler] for system back gesture at the start route.
 * Uses [rememberViewModelStoreNavEntryDecorator] to fix the Koin bug where
 * LocalViewModelStoreOwner resolves to ComponentActivity instead of the NavEntry.
 *
 * Persistence: uses [navSavedStateConfig()] so the back stack survives process death.
 *
 * @param backStack Ignored on Android. Android always creates its own stack via
 *                  [rememberNavBackStack] with [navSavedStateConfig] for process-death survival.
 */
@Composable
actual fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    start: NotesRoute,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") backStack: NavBackStack<NotesRoute>?,
) {
    val stack: NavBackStack<NotesRoute> = rememberNavBackStackTyped(navSavedStateConfig(), start)

    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit

    val navigator = remember(stack, onExitGraph) {
        NotesNavigator(stack, onExitGraph)
    }

    CompositionLocalProvider(
        LocalNotesNavigator provides navigator,
    ) {
        // Intercept system back at the start route to exit the nested graph.
        BackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<NotesRoute.List> { NotesListScreen(it) }
                entry<NotesRoute.Preview> { route -> NotePreviewScreen(route) }
                entry<NotesRoute.Editor> { route -> NoteEditorScreen(route) }
            },
        )
    }
}
