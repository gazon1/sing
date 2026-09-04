package com.singularity.todo.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistUseCase
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
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val dueTime: kotlinx.datetime.LocalTime? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val checklistItems: List<ChecklistItemUi> = emptyList(),
    val newChecklistItem: String = "",
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

data class ChecklistItemUi(
    val id: String,
    val title: String,
    val isCompleted: Boolean = false,
)

sealed interface TaskEditorIntent {
    data class TitleChanged(val text: String) : TaskEditorIntent
    data class DescriptionChanged(val text: String) : TaskEditorIntent
    data class DueDateChanged(val date: kotlinx.datetime.LocalDate?) : TaskEditorIntent
    data class DueTimeChanged(val time: kotlinx.datetime.LocalTime?) : TaskEditorIntent
    data class ProjectChanged(val projectId: String?) : TaskEditorIntent
    data class TagsChanged(val tagIds: List<String>) : TaskEditorIntent
    data class NewChecklistItemChanged(val text: String) : TaskEditorIntent
    data object AddChecklistItem : TaskEditorIntent
    data class ToggleChecklistItem(val id: String) : TaskEditorIntent
    data class DeleteChecklistItem(val id: String) : TaskEditorIntent
    data object Save : TaskEditorIntent
    data object ErrorShown : TaskEditorIntent
}

/**
 * Create-task screen VM. The legacy implementation lived entirely inside the
 * Composable (`var title by remember { ... }`, `rememberCoroutineScope`); this
 * refactor moves the state and validation into the VM so the screen is a thin
 * view and `TaskEditorViewModelTest` can exercise the validation path.
 *
 * @param initialDueDate pre-fills the due date field (e.g. when creating from Today tab).
 */
class TaskEditorViewModel(
    private val createTask: CreateTaskUseCase,
    private val clock: Clock,
    private val userId: UserId,
    private val checklistUseCase: com.singularity.todo.feature.checklist.ChecklistUseCase,
    initialDueDate: kotlinx.datetime.LocalDate? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditorUiState(dueDate = initialDueDate))
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun onIntent(intent: TaskEditorIntent) {
        when (intent) {
            is TaskEditorIntent.TitleChanged -> _uiState.update { it.copy(title = intent.text, errorMessage = null) }
            is TaskEditorIntent.DescriptionChanged -> _uiState.update { it.copy(description = intent.text) }
            is TaskEditorIntent.DueDateChanged -> _uiState.update { it.copy(dueDate = intent.date) }
            is TaskEditorIntent.DueTimeChanged -> _uiState.update { it.copy(dueTime = intent.time) }
            is TaskEditorIntent.ProjectChanged -> _uiState.update { it.copy(projectId = intent.projectId) }
            is TaskEditorIntent.TagsChanged -> _uiState.update { it.copy(tagIds = intent.tagIds) }
            is TaskEditorIntent.NewChecklistItemChanged -> _uiState.update { it.copy(newChecklistItem = intent.text) }
            TaskEditorIntent.AddChecklistItem -> addChecklistItem()
            is TaskEditorIntent.ToggleChecklistItem -> toggleChecklistItem(intent.id)
            is TaskEditorIntent.DeleteChecklistItem -> deleteChecklistItem(intent.id)
            TaskEditorIntent.ErrorShown -> _uiState.update { it.copy(errorMessage = null) }
            TaskEditorIntent.Save -> save()
        }
    }

    private fun addChecklistItem() {
        val text = _uiState.value.newChecklistItem.trim()
        if (text.isBlank()) return
        _uiState.update { st ->
            st.copy(
                checklistItems = st.checklistItems + ChecklistItemUi(
                    id = java.util.UUID.randomUUID().toString(),
                    title = text,
                    isCompleted = false,
                ),
                newChecklistItem = "",
            )
        }
    }

    private fun toggleChecklistItem(id: String) {
        _uiState.update { st ->
            st.copy(
                checklistItems = st.checklistItems.map { item ->
                    if (item.id == id) item.copy(isCompleted = !item.isCompleted) else item
                }
            )
        }
    }

    private fun deleteChecklistItem(id: String) {
        _uiState.update { st ->
            st.copy(checklistItems = st.checklistItems.filter { it.id != id })
        }
    }

    private fun save() = viewModelScope.launch {
        val current = _uiState.value
        if (current.saving) return@launch

        val input = try {
            TasksDomain.createInput(
                title = current.title,
                description = current.description.ifBlank { null },
                dueDate = current.dueDate,
                userId = userId,
            )
        } catch (e: AppError.Validation) {
            _uiState.update { it.copy(errorMessage = e.message) }
            return@launch
        }

        _uiState.update { it.copy(saving = true, errorMessage = null) }

        val taskResult = createTask(input)

        taskResult
            .onSuccess { taskId ->
                // Save checklist items after task creation
                if (current.checklistItems.isNotEmpty()) {
                    val checklistItems = current.checklistItems.map { ui ->
                        com.singularity.todo.feature.checklist.ChecklistItem(
                            id = com.singularity.todo.feature.checklist.ChecklistItemId.fromString(ui.id),
                            taskId = taskId.value,
                            title = ui.title,
                            isCompleted = ui.isCompleted,
                            sortOrder = 0,
                        )
                    }
                    checklistUseCase.createBatch(taskId.value, checklistItems)
                }
                _events.emit(UiEvent.NavigateBack)
            }
            .onFailure {
                _uiState.update { it -> it.copy(saving = false) }
                _events.emit(UiEvent.ShowError(it.message ?: "Failed to save"))
            }
    }
}
