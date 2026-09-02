package com.singularity.todo.feature.attachments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AttachmentsUiState(
    val attachments: List<Attachment> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSheet: Boolean = false
)

class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val currentUserId: UserId
) : ViewModel() {

    private val _state = MutableStateFlow(AttachmentsUiState())
    val state: StateFlow<AttachmentsUiState> = _state.asStateFlow()

    fun watchAttachments(taskId: TaskId) {
        viewModelScope.launch {
            repository.watchByTask(taskId, currentUserId).collect { attachments ->
                _state.update { it.copy(attachments = attachments, isLoading = false) }
            }
        }
    }

    fun showSheet() { _state.update { it.copy(showSheet = true) } }
    fun hideSheet() { _state.update { it.copy(showSheet = false) } }

    fun addUrlAttachment(taskId: TaskId, url: String, title: String?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            repository.addUrlAttachment(taskId, currentUserId, url, title)
                .onFailure { e -> _state.update { it.copy(error = e.message, isLoading = false) } }
                .onSuccess { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun saveFileAttachment(taskId: TaskId, sourcePath: String, mimeType: String?) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            repository.saveFileAttachment(taskId, currentUserId, sourcePath, mimeType)
                .onFailure { e -> _state.update { it.copy(error = e.message, isLoading = false) } }
                .onSuccess { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun delete(attachmentId: com.singularity.todo.core.attachments.AttachmentId) {
        viewModelScope.launch {
            repository.delete(attachmentId)
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }
}
