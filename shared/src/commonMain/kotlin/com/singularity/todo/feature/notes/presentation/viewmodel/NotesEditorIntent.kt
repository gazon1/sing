package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.notes.NoteAiAction

/**
 * Intents for the note editor screen.
 */
sealed interface NotesEditorIntent : MviIntent {
    /** Open an existing note for editing. */
    data class OpenNote(val noteId: String) : NotesEditorIntent

    /** Create a new note (generate id, open empty editor). */
    data object CreateNote : NotesEditorIntent

    /** Update the note title. */
    data class EditTitle(val title: String) : NotesEditorIntent

    /** Update the note body HTML. */
    data class EditBody(val html: String) : NotesEditorIntent

    /** Trigger an immediate save (bypasses debounce). */
    data object SaveNow : NotesEditorIntent

    /** Close the editor, discarding any unsaved changes. */
    data object Close : NotesEditorIntent

    /** Improve the current note with AI. */
    data object ImproveNote : NotesEditorIntent

    /** Run a specific AI action. */
    data class RunAiAction(val action: NoteAiAction) : NotesEditorIntent

    /** Dismiss the current error banner. */
    data object DismissError : NotesEditorIntent
}
