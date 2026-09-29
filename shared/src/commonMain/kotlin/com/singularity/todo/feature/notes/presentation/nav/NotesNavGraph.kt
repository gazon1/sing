package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.NotesRoute

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
 */
@Composable
expect fun NotesNavGraph(
    navCallbacks: NavCallbacks,
    start: NotesRoute = NotesRoute.List,
    modifier: Modifier = Modifier,
)
