package com.singularity.todo.feature.nav

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
sealed interface NotesRoute : AppNavKey {

    /** List / home screen — the single entry point of the notes nested graph. */
    @Serializable
    data object List : NotesRoute

    /** Read-only preview of a single note. */
    @Serializable
    data class Preview(val noteId: NoteId) : NotesRoute

    /**
     * Note editor — used for both creating a new note and editing an existing one.
     *
     * @param noteId The note to edit, or null to create a new note.
     * @param taskId When non-null, the new note is pre-attached to this task
     *               (set via [com.singularity.todo.feature.notes.NotesRepository.createForTask]).
     *               Always null when [noteId] is non-null (separate route variants
     *               prevent the invalid state of editing an existing note while also
     *               creating one attached to a different task).
     */
    @Serializable
    data class Editor(val noteId: NoteId? = null, val taskId: TaskId? = null) : NotesRoute
}
