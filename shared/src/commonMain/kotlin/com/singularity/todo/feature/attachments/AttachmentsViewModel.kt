package com.singularity.todo.feature.attachments

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tasks.domain.model.TaskId

/** Placeholder state — AttachmentsViewModel only emits events. */
sealed interface AttachmentsUiState {
    data object Idle : AttachmentsUiState
}

sealed interface AttachmentsIntent : MviIntent {
    data class AddUrl(val taskId: TaskId, val url: String, val title: String?) : AttachmentsIntent
    data class SaveFile(val taskId: TaskId, val sourcePath: String, val mimeType: String?) : AttachmentsIntent
    data class Delete(val attachmentId: AttachmentId) : AttachmentsIntent
}

/**
 * Attachments sheet ViewModel (per task).
 *
 * Owns: attachment list for the given task.
 * Triggers: add file attachment, add URL attachment, delete attachment.
 * One-shot events: [AttachmentsUiEvent.ShowError].
 *
 * @see AttachmentsUiState
 * @see AttachmentsIntent
 * @see AttachmentsUiEvent
 */
class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<AttachmentsUiState, AttachmentsIntent, AttachmentsUiEvent>(
        initialState = AttachmentsUiState.Idle,
        scope = scope,
    ) {

    override fun onIntent(intent: AttachmentsIntent) {
        when (intent) {
            is AttachmentsIntent.AddUrl -> addUrl(intent)
            is AttachmentsIntent.SaveFile -> saveFile(intent)
            is AttachmentsIntent.Delete -> delete(intent)
        }
    }

    private fun addUrl(intent: AttachmentsIntent.AddUrl) {
        emitError("Failed to add link", AttachmentsUiEvent::ShowError) {
            repository.addUrlAttachment(intent.taskId, intent.url, intent.title)
        }
    }

    private fun saveFile(intent: AttachmentsIntent.SaveFile) {
        emitError("Failed to save file", AttachmentsUiEvent::ShowError) {
            repository.saveFileAttachment(intent.taskId, intent.sourcePath, intent.mimeType)
        }
    }

    private fun delete(intent: AttachmentsIntent.Delete) {
        emitError("Delete failed", AttachmentsUiEvent::ShowError) {
            repository.delete(intent.attachmentId)
        }
    }
}
