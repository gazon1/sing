package com.singularity.todo.feature.attachments

import com.singularity.todo.core.ui.mvi.MviEvent

/**
 * One-shot events emitted by [AttachmentsViewModel].
 */
sealed interface AttachmentsUiEvent : MviEvent {
    data class ShowError(val message: String) : AttachmentsUiEvent
}
