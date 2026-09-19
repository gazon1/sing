package com.singularity.todo.feature.checklist

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.ui.state.updateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

data class ChecklistEditorState(
    val taskId: String = "",
    val items: List<ChecklistItemUi> = emptyList(),
    val newItemText: String = "",
    val adding: Boolean = false,
    val errorMessage: String? = null,
)

data class ChecklistItemUi(val id: ChecklistItemId, val title: String, val isCompleted: Boolean)

sealed interface ChecklistEditorIntent {
    data object Load : ChecklistEditorIntent
    data class NewItemTextChanged(val text: String) : ChecklistEditorIntent
    data object AddItem : ChecklistEditorIntent
    data class ToggleItem(val id: ChecklistItemId) : ChecklistEditorIntent
    data class DeleteItem(val id: ChecklistItemId) : ChecklistEditorIntent
    data object ErrorShown : ChecklistEditorIntent
}

/**
 * Checklist editor sheet ViewModel (inline checklist within a task).
 *
 * Owns: checklist items for a single task.
 * Triggers: add item, toggle item, delete item, load checklist.
 * One-shot events: [ChecklistEditorIntent.ErrorShown] (error acknowledged).
 *
 * @see ChecklistEditorState
 * @see ChecklistEditorIntent
 */
class ChecklistEditorViewModel(
    private val checklistUseCase: ChecklistUseCase,
    private val checklistRepository: ChecklistRepository,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        checklistUseCase: ChecklistUseCase,
        checklistRepository: ChecklistRepository,
    ) : this(
        checklistUseCase = checklistUseCase,
        checklistRepository = checklistRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _state = MutableStateFlow(ChecklistEditorState())
    val state: StateFlow<ChecklistEditorState> = _state.asStateFlow()

    fun bindToTask(taskId: String) {
        _state.updateState { it.copy(taskId = taskId) }
        scope.launch {
            checklistRepository.watchByTask(taskId).collect { items ->
                _state.updateState { st ->
                    st.copy(
                        items = items.map { ChecklistItemUi(it.id, it.title, it.isCompleted) },
                    )
                }
            }
        }
    }

    fun onIntent(intent: ChecklistEditorIntent) {
        when (intent) {
            ChecklistEditorIntent.Load -> { /* handled by bindToTask */ }
            is ChecklistEditorIntent.NewItemTextChanged -> _state.updateState { it.copy(newItemText = intent.text) }
            ChecklistEditorIntent.AddItem -> addItem()
            is ChecklistEditorIntent.ToggleItem -> toggleItem(intent.id)
            is ChecklistEditorIntent.DeleteItem -> deleteItem(intent.id)
            ChecklistEditorIntent.ErrorShown -> _state.updateState { it.copy(errorMessage = null) }
        }
    }

    private fun addItem() {
        val text = _state.value.newItemText.trim()
        if (text.isBlank()) return
        val taskId = _state.value.taskId
        if (taskId.isBlank()) return

        scope.launch {
            _state.updateState { it.copy(adding = true) }
            checklistUseCase.addItem(taskId, text)
                .onSuccess {
                    _state.updateState { st -> st.copy(newItemText = "", adding = false) }
                }
                .onFailure { err: Throwable ->
                    _state.updateState { st -> st.copy(adding = false, errorMessage = err.message) }
                }
        }
    }

    private fun toggleItem(id: ChecklistItemId) {
        val item = _state.value.items.find { it.id == id } ?: return
        val taskId = _state.value.taskId

        scope.launch {
            checklistUseCase.toggleItem(id, item.title, taskId, item.isCompleted)
                .onFailure { err: Throwable ->
                    _state.updateState { st -> st.copy(errorMessage = err.message) }
                }
        }
    }

    private fun deleteItem(id: ChecklistItemId) {
        scope.launch {
            checklistRepository.delete(id)
                .onFailure { err: Throwable ->
                    _state.updateState { st -> st.copy(errorMessage = err.message) }
                }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
