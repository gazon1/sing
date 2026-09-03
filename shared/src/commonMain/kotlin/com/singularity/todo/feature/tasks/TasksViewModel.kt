package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Empty(val filter: TaskFilter) : TasksUiState
    data class Content(val filter: TaskFilter, val tasks: List<Task>) : TasksUiState
    data class Error(val message: String) : TasksUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val getTasks: GetTasksUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val toggleComplete: ToggleCompleteUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _filter = MutableStateFlow<TaskFilter>(TaskFilter.Today)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val currentUserId = UserId.fromString(runBlocking { settingsRepository.userId.first() })

    val state: StateFlow<TasksUiState> = _filter
        .flatMapLatest { filter ->
            getTasks(currentUserId, filter)
        }
        .map<List<Task>, TasksUiState> { tasks ->
            if (tasks.isEmpty()) TasksUiState.Empty(_filter.value)
            else TasksUiState.Content(_filter.value, tasks)
        }
        .catch { emit(TasksUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TasksUiState.Loading)

    fun setFilter(filter: TaskFilter) {
        _filter.value = filter
    }

    fun delete(id: TaskId) = viewModelScope.launch {
        deleteTask(id)
    }

    fun toggle(id: TaskId) = viewModelScope.launch {
        toggleComplete(id)
    }
}
