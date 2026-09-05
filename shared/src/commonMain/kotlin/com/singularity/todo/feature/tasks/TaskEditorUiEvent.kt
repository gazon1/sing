package com.singularity.todo.feature.tasks

/**
 * One-shot events emitted by [TaskEditorViewModel].
 */
sealed interface TaskEditorUiEvent {
    /** Navigate back after successful save. */
    data object NavigateBack : TaskEditorUiEvent

    /** Save failed with an error. */
    data class Error(val message: String) : TaskEditorUiEvent
}
