package com.singularity.todo.feature.notes.presentation.nav

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavBackStack

/**
 * Provides [NotesNavigator] to the notes feature screens.
 * Must be provided by [NotesNavGraph].
 *
 * Screens MUST NOT access [LocalNavBackStack] directly — use [NotesNavigator]
 * to preserve back semantics (size <= 1 → onExitGraph).
 */
val LocalNotesNavigator = compositionLocalOf<NotesNavigator> {
    error("NotesNavigator not provided — wrap with NotesNavGraph")
}

/**
 * Internal back stack accessor for use within the nav package only.
 * Not accessible outside `feature/notes/presentation/nav/`.
 */
internal val LocalNavBackStack = compositionLocalOf<NavBackStack<NotesRoute>> {
    error("NavBackStack<NotesRoute> not provided")
}
