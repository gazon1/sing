package com.singularity.todo.feature.tasks

/**
 * One-shot events emitted by [TaskDetailViewModel].
 */
sealed interface TaskDetailUiEvent {
    /** Field or relation was saved successfully. */
    data class Saved(val message: String) : TaskDetailUiEvent

    /** A user-facing error. */
    data class Error(val message: String) : TaskDetailUiEvent

    /** Navigate back. */
    data object NavigateBack : TaskDetailUiEvent

    // Sheet / dialog triggers
    data object OpenDatePicker : TaskDetailUiEvent
    data object OpenTimePicker : TaskDetailUiEvent
    data object OpenPrioritySheet : TaskDetailUiEvent
    data object OpenProjectSheet : TaskDetailUiEvent
    data object OpenTagSheet : TaskDetailUiEvent
    data object OpenReminderSheet : TaskDetailUiEvent
    data object OpenAttachmentSheet : TaskDetailUiEvent
    data object ConfirmDelete : TaskDetailUiEvent
    data object ConfirmArchive : TaskDetailUiEvent
}
