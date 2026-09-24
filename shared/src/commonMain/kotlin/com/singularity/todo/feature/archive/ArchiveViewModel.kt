package com.singularity.todo.feature.archive

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState
    data class Content(val tasks: List<Task>, val refreshing: Boolean = false) : ArchiveUiState
    data class Error(val message: String) : ArchiveUiState
}

/**
 * Archive screen ViewModel (soft-deleted tasks).
 *
 * Owns: archived task list.
 * Triggers: restore task, permanently delete task.
 * One-shot events: [ArchiveUiEvent.ShowError].
 *
 * @see ArchiveUiState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val archiveRepo: TaskDaoArchiveRepository,
    taskRepo: TaskRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    private val _state = MutableStateFlow<ArchiveUiState>(ArchiveUiState.Loading)
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    private val _events = MutableSharedFlow<ArchiveUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ArchiveUiEvent> = _events.asSharedFlow()

    init {
        addCloseable(scope)
        scope.launch {
            combine(
                taskRepo.observeByFilter(TaskFilter.Trash),
                _refreshing,
            ) { tasks: List<Task>, refreshing: Boolean ->
                ArchiveUiState.Content(tasks, refreshing = refreshing) as ArchiveUiState
            }
                .catch { emit(ArchiveUiState.Error(it.message ?: "Error")) }
                .collect { _state.value = it }
        }
    }

    fun refresh() = scope.launch {
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
