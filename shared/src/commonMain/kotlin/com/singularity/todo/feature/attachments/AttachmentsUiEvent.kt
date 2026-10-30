package com.singularity.todo.feature.attachments

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.ui.MviEvent

/**
 * One-shot events emitted by [AttachmentsViewModel].
 */
sealed interface AttachmentsUiEvent : MviEvent {
    data class ShowError(val message: String) : AttachmentsUiEvent
    data class UndoDelete(val attachmentId: AttachmentId) : AttachmentsUiEvent
    data class UndoDeleteAttachment(val attachmentId: AttachmentId) : AttachmentsUiEvent
}
