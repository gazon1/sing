package com.singularity.todo.feature.notes.presentation.nav

import androidx.navigation3.runtime.NavKey
import com.singularity.todo.feature.notes.NoteId
import kotlinx.serialization.Serializable

/**
 * Navigation routes for the notes nested graph.
 *
 * Lives inside [NotesNavGraph] which provides its own NavBackStack.
 *
 * @Serializable because [NotesNavGraph] uses a [SavedStateConfiguration]-backed
 * [androidx.navigation3.runtime.rememberNavBackStack], which serializes the stack.
 * All [NavKey] subtypes must be serializable for the [SaveableStateHolder] encoder.
 */
@Serializable
sealed interface NotesRoute : NavKey {

    /** List / home screen — the single entry point of the notes nested graph. */
    @Serializable
    data object List : NotesRoute

    /** Read-only preview of a single note. */
    @Serializable
    data class Preview(val noteId: NoteId) : NotesRoute

    /** Note editor — used for both creating a new note and editing an existing one. */
    @Serializable
    data class Editor(val noteId: NoteId? = null) : NotesRoute
}
