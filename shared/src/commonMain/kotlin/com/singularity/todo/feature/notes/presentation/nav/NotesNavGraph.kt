package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.rememberAppEntryDecorators
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.nav.SystemBackHandler
import com.singularity.todo.feature.nav.rememberAppNavBackStack
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.notes.presentation.screen.NoteEditorScreen
import com.singularity.todo.feature.notes.presentation.screen.NotePreviewScreen
import com.singularity.todo.feature.notes.presentation.screen.NotesListScreen

/**
 * The nested navigation graph of the notes feature.
 *
 * Provides its own [NavBackStack] with [NotesRoute] keys, independent of the outer
 * app back stack. Screens inside use [LocalNotesNavigator] to navigate without
 * manual callbacks.
 *
 * @param navCallbacks the outer [NavCallbacks] for cross-graph navigation. Used to
 *   build the [onExitGraph][NotesNavigator.onExitGraph] callback.
 * @param start the initial [NotesRoute] to show. Defaults to [NotesRoute.List].
 * @param modifier compose modifier for the inner [NavDisplay].
 * @param backStack optional pre-created stack. Desktop passes a stack hoisted into
 *   the shell's composition, so the nested state survives a tab switch.
 */
@Composable
fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    start: NotesRoute = NotesRoute.List,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NotesRoute>? = null,
) {
    val stack: NavBackStack<NotesRoute> = backStack ?: rememberAppNavBackStack(start)
    val onExitGraph: (AppDestination?) -> Unit = navCallbacks.graphExit
    val navigator = remember(stack, onExitGraph) {
        NotesNavigator(stack, onExitGraph)
    }
    CompositionLocalProvider(
        LocalNotesNavigator provides navigator,
    ) {
        // Intercept the system back at the start route to exit the nested graph.
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }

        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { navigator.back() },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = notesScreenEntryProvider(),
        )
    }
}

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Notes
 * nested graph — the map from each [NotesRoute] variant to the screen that renders it.
 */
@Composable
internal fun notesScreenEntryProvider() = entryProvider {
    entry<NotesRoute.List>(clazzContentKey = { it.stableContentKey() }) { NotesListScreen(it) }
    entry<NotesRoute.Preview>(clazzContentKey = { it.stableContentKey() }) { route -> NotePreviewScreen(route) }
    entry<NotesRoute.Editor>(clazzContentKey = { it.stableContentKey() }) { route -> NoteEditorScreen(route) }
}
