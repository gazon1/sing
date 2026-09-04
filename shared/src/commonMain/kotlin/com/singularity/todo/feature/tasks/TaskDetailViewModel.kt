package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.ui.components.FieldMode
import com.singularity.todo.core.ui.components.UiEvent
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

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Loaded(
        val task: Task,
        val titleField: FieldMode = FieldMode.View,
        val descriptionField: FieldMode = FieldMode.View,
    ) : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
}

sealed interface TaskDetailField {
    data object Title : TaskDetailField
    data object Description : TaskDetailField
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModel(
    private val taskRepo: TaskRepository,
    private val updateTask: UpdateTaskUseCase,
) : ViewModel() {

    private val _taskId = MutableStateFlow<TaskId?>(null)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** Reactive state — UI calls [start] once and observes this forever. */
    val state: StateFlow<TaskDetailUiState> = _taskId
        .flatMapLatest { id ->
            if (id == null) {
                kotlinx.coroutines.flow.flowOf(TaskDetailUiState.Loading)
            } else {
                taskRepo.watchTask(id)
                    .map<Task?, TaskDetailUiState> { task ->
                        if (task == null) TaskDetailUiState.Error("Not found")
                        else TaskDetailUiState.Loaded(task)
                    }
            }
        }
        .catch { emit(TaskDetailUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TaskDetailUiState.Loading)

    fun start(taskId: TaskId) {
        _taskId.value = taskId
    }

    fun saveField(current: Task, field: TaskDetailField, draft: String) = viewModelScope.launch {
        val updated = when (field) {
            TaskDetailField.Title -> current.copy(title = draft)
            TaskDetailField.Description -> current.copy(description = draft.ifBlank { null })
        }
        updateTask(updated)
            .onSuccess { _events.emit(UiEvent.ShowDialog("Saved", "Field updated")) }
            .onFailure { _events.emit(UiEvent.ShowError(it.message ?: "Save failed")) }
    }
}
