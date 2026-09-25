package com.singularity.todo.feature.notes.presentation

import com.singularity.todo.core.ui.mvi.MviIntent
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder

/**
 * User intents for the notes list screen.
 */
sealed interface NotesIntent : MviIntent {
    data class Delete(val id: NoteId) : NotesIntent
    data class TogglePin(val id: NoteId) : NotesIntent
    data class SetFilter(val filter: NoteFilter) : NotesIntent
    data class SetSortOrder(val order: NoteSortOrder) : NotesIntent
    data class EnterSelection(val id: NoteId) : NotesIntent
    data class ToggleSelection(val id: NoteId) : NotesIntent
    data object ExitSelection : NotesIntent
    data object DeleteSelected : NotesIntent
    data class CreateNote(val title: String) : NotesIntent
    data class DeleteNote(val id: NoteId) : NotesIntent
}
