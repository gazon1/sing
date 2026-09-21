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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val archiveRepo: ArchiveRepository,
    private val taskRepo: TaskRepository,
    private val scope: AutoCloseableCoroutineScope,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Production constructor — Koin uses this. */
    constructor(
        archiveRepo: ArchiveRepository,
        taskRepo: TaskRepository,
    ) : this(
        archiveRepo = archiveRepo,
        taskRepo = taskRepo,
        scope = AutoCloseableCoroutineScope(),
    )

    private val _refreshing = MutableStateFlow(false)
    private val _events = MutableSharedFlow<ArchiveUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ArchiveUiEvent> = _events.asSharedFlow()

    val state: StateFlow<ArchiveUiState> = taskRepo.observeByFilter(TaskFilter.Trash)
        .map<List<Task>, ArchiveUiState> { tasks ->
            ArchiveUiState.Content(tasks, refreshing = _refreshing.value)
        }
        .catch { emit(ArchiveUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, sharingStarted(), ArchiveUiState.Loading)

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
