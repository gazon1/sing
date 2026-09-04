package com.singularity.todo.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ui.components.UiEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the project editor screen.
 */
data class ProjectEditorUiState(
    val name: String = "",
    val color: Int = DEFAULT_COLOR,
    val description: String = "",
    val saving: Boolean = false,
    val errorMessage: String? = null,
) {
    companion object {
        val DEFAULT_COLOR = 0xFF1976D2.toInt() // blue
    }
}

sealed interface ProjectEditorIntent {
    data class NameChanged(val name: String) : ProjectEditorIntent
    data class ColorChanged(val color: Int) : ProjectEditorIntent
    data class DescriptionChanged(val description: String) : ProjectEditorIntent
    data object Save : ProjectEditorIntent
    data object ErrorShown : ProjectEditorIntent
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectEditorViewModel(
    private val createProject: CreateProjectUseCase,
    private val currentUser: CurrentUser,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectEditorUiState())
    val state: StateFlow<ProjectEditorUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun processIntent(intent: ProjectEditorIntent) {
        when (intent) {
            is ProjectEditorIntent.NameChanged ->
                _state.value = _state.value.copy(name = intent.name, errorMessage = null)
            is ProjectEditorIntent.ColorChanged ->
                _state.value = _state.value.copy(color = intent.color)
            is ProjectEditorIntent.DescriptionChanged ->
                _state.value = _state.value.copy(description = intent.description)
            ProjectEditorIntent.ErrorShown ->
                _state.value = _state.value.copy(errorMessage = null)
            ProjectEditorIntent.Save -> save()
        }
    }

    private fun save() {
        val current = _state.value
        val validationError = validateName(current.name)
        if (validationError != null) {
            _state.value = current.copy(errorMessage = validationError)
            return
        }

        _state.value = current.copy(saving = true, errorMessage = null)
        viewModelScope.launch {
            val userId = currentUser.current.value
            val input = CreateProjectInput(
                name = current.name.trim(),
                color = current.color,
                description = current.description.ifBlank { null },
                userId = userId,
            )
            val result = createProject(input)
            result.fold(
                onSuccess = { _events.emit(UiEvent.NavigateBack) },
                onFailure = {
                    _state.value = _state.value.copy(
                        saving = false,
                        errorMessage = it.message ?: "Failed to create project"
                    )
                }
            )
        }
    }

    private fun validateName(name: String): String? = when {
        name.isBlank() -> "Name cannot be blank"
        name.length > 50 -> "Name too long (max 50 characters)"
        else -> null
    }
}
