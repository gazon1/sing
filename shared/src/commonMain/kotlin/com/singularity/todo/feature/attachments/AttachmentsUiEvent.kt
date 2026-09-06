package com.singularity.todo.feature.attachments

/**
 * One-shot events emitted by [AttachmentsViewModel].
 *
 * [Error] replaces the `error = message` pattern on [AttachmentsUiState].
 * [clearError] is no longer needed — events are one-shot by nature.
 */
sealed interface AttachmentsUiEvent {
    data class Error(val message: String) : AttachmentsUiEvent
}
