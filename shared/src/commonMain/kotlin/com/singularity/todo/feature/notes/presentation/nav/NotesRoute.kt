package com.singularity.todo.feature.notes.presentation.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.notes.NoteId

/**
 * Navigation routes for the notes nested graph.
 *
 * Lives inside [NotesNavGraph] which provides its own NavBackStack.
 * NOT @Serializable — the nested graph uses an empty SavedStateConfiguration
 * so routes are kept in memory only. If SavedState is needed, add
 * @Serializable and register polymorphic serializers in SavedStateConfiguration.
 */
sealed interface NotesRoute : NavKey {

    /** List / home screen — the single entry point of the notes nested graph. */
    data object List : NotesRoute

    /** Read-only preview of a single note. */
    data class Preview(val noteId: NoteId) : NotesRoute

    /** Note editor — used for both creating a new note and editing an existing one. */
    data class Editor(val noteId: NoteId? = null) : NotesRoute
}
