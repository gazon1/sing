package com.singularity.todo.feature.attachments

import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.mvi.MviIntent
import com.singularity.todo.core.ui.mvi.MviViewModel
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.launch

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

    init {
        addCloseable(scope)
    }

    override fun onIntent(intent: AttachmentsIntent) {
        when (intent) {
            is AttachmentsIntent.AddUrl -> scope.launch { addUrl(intent) }
            is AttachmentsIntent.SaveFile -> scope.launch { saveFile(intent) }
            is AttachmentsIntent.Delete -> scope.launch { delete(intent) }
        }
    }

    private suspend fun addUrl(intent: AttachmentsIntent.AddUrl) {
        repository.addUrlAttachment(intent.taskId, intent.url, intent.title)
            .onFailure { emit(AttachmentsUiEvent.ShowError(it.message ?: "Failed to add link")) }
    }

    private suspend fun saveFile(intent: AttachmentsIntent.SaveFile) {
        repository.saveFileAttachment(intent.taskId, intent.sourcePath, intent.mimeType)
            .onFailure { emit(AttachmentsUiEvent.ShowError(it.message ?: "Failed to save file")) }
    }

    private suspend fun delete(intent: AttachmentsIntent.Delete) {
        repository.delete(intent.attachmentId)
            .onFailure { emit(AttachmentsUiEvent.ShowError(it.message ?: "Delete failed")) }
    }
}
