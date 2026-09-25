package com.singularity.todo.feature.notes

import com.singularity.todo.core.ui.mvi.MviEvent

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
     * Autosave or manual save succeeded — triggers the "Saved" pill animation
     * in [NoteEditorScreen]. One-shot signal with no replay.
     */
    data object SavedPulse : NotesUiEvent
}
