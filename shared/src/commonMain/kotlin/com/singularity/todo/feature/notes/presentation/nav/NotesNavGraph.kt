package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import com.singularity.todo.feature.nav.NavCallbacks

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
 *   - Android: includes [androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator]
 *     for per-entry VM scoping and [androidx.activity.compose.BackHandler] for system back gesture
 *   - JVM: no decorators needed (desktop has no ComponentActivity scoping issue)
 *
 * @param navCallbacks The outer [NavCallbacks] for cross-graph navigation.
 *                     Used to build the [onExitGraph][NotesNavigator.onExitGraph] callback internally.
 * @param modifier Compose modifier for the inner [NavDisplay][androidx.navigation3.ui.NavDisplay].
 */
@Composable
expect fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    modifier: Modifier = Modifier,
)
