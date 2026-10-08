package com.singularity.todo.feature.notes

import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [NotesViewModel].
 */
sealed interface NotesUiEvent : MviEvent {
    /** AI action returned a result to show the user. */
    data class AiResult(val text: String) : NotesUiEvent

    /** Save failed. */
    data class SaveFailed(val message: String) : NotesUiEvent

    /** Operation failed. */
    data class Error(val message: String) : NotesUiEvent

    /** Navigate back. */
    data object NavigateBack : NotesUiEvent

    /**
     * A note was created — open the editor on **the id the repository persisted**.
     *
     * The id cannot come back from a synchronous call, because the write is launched. It
     * also cannot be generated locally: `NotesRepository.createNoteWithTitle` generates
     * its own, and a locally generated id names a note that does not exist. The screen
     * used to do exactly that, so "Create your first note" opened an empty editor for a
     * phantom id and orphaned the note that was really created.
     */
    data class NavigateToEditor(val noteId: NoteId) : NotesUiEvent

    /**
     * Autosave or manual save succeeded — triggers the "Saved" pill animation
     * in [NoteEditorScreen]. One-shot signal with no replay.
     */
    data object SavedPulse : NotesUiEvent

    /**
     * A note was soft-deleted and the UI should show a 5-second undo snackbar.
     * The snackbar is driven by [com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel.pendingDelete];
     * this event is consumed by [CollectEvents] and does not trigger UI directly.
     *
     * @param noteId The deleted note id.
     * @param title  Short label for the snackbar message.
     */
    data class UndoDelete(val noteId: NoteId, val title: String) : NotesUiEvent
}
