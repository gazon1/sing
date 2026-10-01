package com.singularity.todo.feature.archive

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.archive.data.TaskDaoArchiveRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface ArchiveUiState {
    data object Loading : ArchiveUiState
    data class Content(val tasks: List<Task>, val refreshing: Boolean = false) : ArchiveUiState
    data class Error(val message: String) : ArchiveUiState
}

sealed interface ArchiveIntent : MviIntent {
    data object Refresh : ArchiveIntent
}

/**
 * Archive screen ViewModel (soft-deleted tasks).
 *
 * Owns: archived task list.
 * Triggers: restore task, permanently delete task.
 * One-shot events: [ArchiveUiEvent.Archived], [ArchiveUiEvent.Error].
 *
 * @see ArchiveUiState
 * @see ArchiveIntent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val archiveRepo: TaskDaoArchiveRepository,
    taskRepo: TaskRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ArchiveUiState, ArchiveIntent, ArchiveUiEvent>(
        initialState = ArchiveUiState.Loading,
        scope = scope,
    ) {

    private val refreshing = MutableStateFlow(false)

    init {
        addCloseable(scope)
        scope.launch {
            combine(
                taskRepo.observeByFilter(TaskFilter.Trash),
                refreshing,
            ) { tasks: List<Task>, r: Boolean ->
                ArchiveUiState.Content(tasks, refreshing = r) as ArchiveUiState
            }.catch { e ->
                updateState {
                    ArchiveUiState.Error(
                        e.message
                            ?: "Error",
                    )
                }
            }
                .collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: ArchiveIntent) {
        when (intent) {
            ArchiveIntent.Refresh -> scope.launch { refresh() }
        }
    }

    private suspend fun refresh() {
        refreshing.value = true
        val result = archiveRepo.archiveCompletedTasks()
        refreshing.value = false
        result.onSuccess { count ->
            if (count > 0) emit(ArchiveUiEvent.Archived("Moved $count tasks to archive"))
        }
            .onFailure { e ->
                emit(
                    ArchiveUiEvent.Error(
                        e.message
                            ?: "Archive failed",
                    ),
                )
            }
    }
}
