package com.singularity.todo.feature.notes.presentation

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder

/**
 * User intents for the notes list screen.
 */
sealed interface NotesIntent : MviIntent {
    data class Delete(val id: NoteId) : NotesIntent
    data class TogglePin(val id: NoteId) : NotesIntent
    data class Archive(val id: NoteId) : NotesIntent
    data class Unarchive(val id: NoteId) : NotesIntent
    data class SetFilter(val filter: NoteFilter) : NotesIntent
    data class SetSortOrder(val order: NoteSortOrder) : NotesIntent
    data class SearchQueryChanged(val query: String) : NotesIntent
    data class EnterSelection(val id: NoteId) : NotesIntent
    data class ToggleSelection(val id: NoteId) : NotesIntent
    data object ExitSelection : NotesIntent
    data object DeleteSelected : NotesIntent
    data class CreateNote(val title: String) : NotesIntent
    data class DeleteNote(val id: NoteId) : NotesIntent

    /**
     * Restores a note that was soft-deleted and is still within the 5-second undo window.
     * Called by the UI when the user taps "Undo" on the snackbar.
     */
    data class UndoDelete(val id: NoteId) : NotesIntent
}
