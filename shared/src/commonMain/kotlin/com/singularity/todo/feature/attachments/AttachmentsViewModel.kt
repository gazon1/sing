package com.singularity.todo.feature.attachments

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Attachments sheet ViewModel (per task).
 *
 * Owns: attachment list for the given task.
 * Triggers: add file attachment, add URL attachment, delete attachment.
 * One-shot events: [AttachmentsUiEvent.ShowError].
 *
 * @see AttachmentsUiState
 */
class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: AutoCloseableCoroutineScope,
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Production constructor — Koin uses this. */
    constructor(
        repository: AttachmentRepository,
        currentUser: ProfileAwareCurrentUser,
    ) : this(
        repository = repository,
        currentUser = currentUser,
        scope = AutoCloseableCoroutineScope(),
    )

    private val _events = MutableSharedFlow<AttachmentsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AttachmentsUiEvent> = _events.asSharedFlow()

    private val currentUserId get() = currentUser.current

    fun addUrlAttachment(taskId: TaskId, url: String, title: String?) {
        scope.launch {
            repository.addUrlAttachment(taskId, currentUserId, url, title)
                .onFailure { e -> _events.emit(AttachmentsUiEvent.Error(e.message ?: "Failed to add link")) }
        }
    }

    fun saveFileAttachment(taskId: TaskId, sourcePath: String, mimeType: String?) {
        scope.launch {
            repository.saveFileAttachment(taskId, currentUserId, sourcePath, mimeType)
                .onFailure { e -> _events.emit(AttachmentsUiEvent.Error(e.message ?: "Failed to save file")) }
        }
    }

    fun delete(attachmentId: com.singularity.todo.core.attachments.AttachmentId) {
        scope.launch {
            repository.delete(attachmentId)
                .onFailure { e -> _events.emit(AttachmentsUiEvent.Error(e.message ?: "Delete failed")) }
        }
    }
}
