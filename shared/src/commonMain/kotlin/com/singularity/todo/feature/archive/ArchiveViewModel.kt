package com.singularity.todo.feature.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState
    data class Content(val tasks: List<Task>, val refreshing: Boolean = false) : ArchiveUiState
    data class Error(val message: String) : ArchiveUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val archiveRepo: ArchiveRepository,
    private val taskRepo: TaskRepository,
    currentUser: ProfileAwareCurrentUser,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    private val _events = MutableSharedFlow<ArchiveUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ArchiveUiEvent> = _events.asSharedFlow()

    val state: StateFlow<ArchiveUiState> = currentUser.scopedUserId
        .flatMapLatest { uid ->
            taskRepo.watchTasks(uid, TaskFilter.Trash)
                .map<List<Task>, ArchiveUiState> { tasks ->
                    ArchiveUiState.Content(tasks, refreshing = _refreshing.value)
                }
        }
        .catch { emit(ArchiveUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ArchiveUiState.Loading)

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        val result = archiveRepo.archiveCompletedTasks()
        _refreshing.value = false
        result
            .onSuccess { count ->
                if (count > 0) _events.emit(ArchiveUiEvent.Archived("Moved $count tasks to archive"))
            }
            .onFailure { e ->
                _events.emit(ArchiveUiEvent.Error(e.message ?: "Archive failed"))
            }
    }
}
