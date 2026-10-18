package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.presentation.screen.NoteEditorScreen
import com.singularity.todo.feature.notes.presentation.screen.NotePreviewScreen
import com.singularity.todo.feature.notes.presentation.screen.NotesListScreen

/**
 * Creates a nested navigation graph for the notes feature.
 *
 * Provides its own [androidx.navigation3.runtime.NavBackStack] with [NotesRoute] keys,
 * independent of the outer app back stack. Screens inside use [LocalNotesNavigator]
 * to navigate without needing manual callbacks.
 *
 * ## Architecture
 *
 * - [LocalNotesNavigator], [NotesNavigator], [NotesRoute] — commonMain (platform-agnostic)
 * - This function — platform-specific implementations
 *   - Android: includes [androidx.activity.compose.BackHandler] for system back gesture
 *   - JVM: no back handler (desktop has no system back gesture)
 *
 * @param navCallbacks The outer [NavCallbacks] for cross-graph navigation.
 *                     Used to build the [onExitGraph][NotesNavigator.onExitGraph] callback.
 * @param start The initial [NotesRoute] to show. Defaults to [NotesRoute.List].
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 * @param backStack Optional pre-created stack. When provided, the graph uses this stack
 *                  instead of creating a new one. Used by JVM Desktop to pass a stable
 *                  stack created via [rememberInMemoryNavBackStack] in the entry block.
 */
@Composable
expect fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    start: NotesRoute = NotesRoute.List,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NotesRoute>? = null,
)

/**
 * Builds the [entryProvider][androidx.navigation3.runtime.entryProvider] for the Notes
 * nested graph — the map from each [NotesRoute] variant to the screen that renders it.
 *
 * Extracted to commonMain because both platform implementations (Android, JVM) have
 * identical `entryProvider {}` bodies. Duplicating exhaustive `when`-over-sealed is
 * the dangerous kind: adding a variant to one file and forgetting the other compiles
 * cleanly in both and silently breaks at runtime.
 */
@Composable
internal fun notesScreenEntryProvider() = entryProvider {
    entry<NotesRoute.List> { NotesListScreen(it) }
    entry<NotesRoute.Preview> { route -> NotePreviewScreen(route) }
    entry<NotesRoute.Editor> { route -> NoteEditorScreen(route) }
}
