package com.singularity.todo.feature.attachments

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        repository: AttachmentRepository,
        currentUser: ProfileAwareCurrentUser,
    ) : this(
        repository = repository,
        currentUser = currentUser,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
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
