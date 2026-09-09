package com.singularity.todo.feature.attachments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.TaskId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val currentUser: ProfileAwareCurrentUser,
) : ViewModel() {

    private val _events = MutableSharedFlow<AttachmentsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AttachmentsUiEvent> = _events.asSharedFlow()

    private val currentUserId get() = currentUser.current

    fun addUrlAttachment(taskId: TaskId, url: String, title: String?) {
        viewModelScope.launch {
            repository.addUrlAttachment(taskId, currentUserId, url, title)
                .onFailure { e -> _events.emit(AttachmentsUiEvent.Error(e.message ?: "Failed to add link")) }
        }
    }

    fun saveFileAttachment(taskId: TaskId, sourcePath: String, mimeType: String?) {
        viewModelScope.launch {
            repository.saveFileAttachment(taskId, currentUserId, sourcePath, mimeType)
                .onFailure { e -> _events.emit(AttachmentsUiEvent.Error(e.message ?: "Failed to save file")) }
        }
    }

    fun delete(attachmentId: com.singularity.todo.core.attachments.AttachmentId) {
        viewModelScope.launch {
            repository.delete(attachmentId)
                .onFailure { e -> _events.emit(AttachmentsUiEvent.Error(e.message ?: "Delete failed")) }
        }
    }
}
