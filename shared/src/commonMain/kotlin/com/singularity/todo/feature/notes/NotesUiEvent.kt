package com.singularity.todo.feature.notes

/**
 * One-shot events emitted by [NotesViewModel].
 */
sealed interface NotesUiEvent {
    /** AI action returned a result to show the user. */
    data class AiResult(val text: String) : NotesUiEvent

    /** Save failed. */
    data class SaveFailed(val message: String) : NotesUiEvent

    /** Navigate back. */
    data object NavigateBack : NotesUiEvent
}
