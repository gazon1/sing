package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.components.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the create-task screen.
 *
 * The screen is stateless — every field belongs to the VM so it can be tested
 * without Compose and so save/error handling lives in one place.
 */
data class TaskEditorUiState(
    val title: String = "",
    val description: String = "",
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface TaskEditorIntent {
    data class TitleChanged(val text: String) : TaskEditorIntent
    data class DescriptionChanged(val text: String) : TaskEditorIntent
    data object Save : TaskEditorIntent
    data object ErrorShown : TaskEditorIntent
}

/**
 * Create-task screen VM. The legacy implementation lived entirely inside the
 * Composable (`var title by remember { ... }`, `rememberCoroutineScope`); this
 * refactor moves the state and validation into the VM so the screen is a thin
 * view and `TaskEditorViewModelTest` can exercise the validation path.
 */
class TaskEditorViewModel(
    private val createTask: CreateTaskUseCase,
    private val clock: Clock,
    private val userId: UserId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditorUiState())
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun onIntent(intent: TaskEditorIntent) {
        when (intent) {
            is TaskEditorIntent.TitleChanged -> _uiState.update { it.copy(title = intent.text, errorMessage = null) }
            is TaskEditorIntent.DescriptionChanged -> _uiState.update { it.copy(description = intent.text) }
            TaskEditorIntent.ErrorShown -> _uiState.update { it.copy(errorMessage = null) }
            TaskEditorIntent.Save -> save()
        }
    }

    private fun save() = viewModelScope.launch {
        val current = _uiState.value
        if (current.saving) return@launch

        val input = try {
            TasksDomain.createInput(
                title = current.title,
                description = current.description.ifBlank { null },
                userId = userId,
            )
        } catch (e: AppError.Validation) {
            _uiState.update { it.copy(errorMessage = e.message) }
            return@launch
        }

        _uiState.update { it.copy(saving = true, errorMessage = null) }
        createTask(input)
            .onSuccess { _events.emit(UiEvent.NavigateBack) }
            .onFailure {
                _uiState.update { it -> it.copy(saving = false) }
                _events.emit(UiEvent.ShowError(it.message ?: "Failed to save"))
            }
    }
}
