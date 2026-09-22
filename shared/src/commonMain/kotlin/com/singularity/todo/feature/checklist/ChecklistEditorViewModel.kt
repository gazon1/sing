package com.singularity.todo.feature.checklist

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.state.updateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChecklistEditorState(
    val items: List<ChecklistItemUi> = emptyList(),
    val newItemText: String = "",
    val adding: Boolean = false,
    val errorMessage: String? = null,
)

data class ChecklistItemUi(val id: ChecklistItemId, val title: String, val isCompleted: Boolean)

sealed interface ChecklistEditorIntent {
    data class NewItemTextChanged(val text: String) : ChecklistEditorIntent
    data object AddItem : ChecklistEditorIntent
    data class ToggleItem(val id: ChecklistItemId) : ChecklistEditorIntent
    data class DeleteItem(val id: ChecklistItemId) : ChecklistEditorIntent
    data object ErrorShown : ChecklistEditorIntent
}

/**
 * Checklist editor sheet ViewModel (inline checklist within a task).
 *
 * Owns: checklist items for a single task (identified by [taskId]).
 * Triggers: add item, toggle item, delete item.
 * One-shot events: [ChecklistEditorIntent.ErrorShown] (error acknowledged).
 *
 * @param taskId       the parent task to load the checklist for.
 * @param checklistRepository repository for checklist mutations and observation.
 * @param scope        coroutine scope; mandatory — caller provides it via [AutoCloseableCoroutineScope].
 *
 * @see ChecklistEditorState
 * @see ChecklistEditorIntent
 */
class ChecklistEditorViewModel(
    private val taskId: String,
    private val checklistRepository: ChecklistRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
        scope.launch {
            checklistRepository.watchByTask(taskId).collect { items ->
                _state.updateState { st ->
                    st.copy(items = items.map { ChecklistItemUi(it.id, it.title, it.isCompleted) })
                }
            }
        }
    }

    private val _state = MutableStateFlow(ChecklistEditorState())
    val state: StateFlow<ChecklistEditorState> = _state.asStateFlow()

    fun onIntent(intent: ChecklistEditorIntent) {
        when (intent) {
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

        scope.launch {
            _state.updateState { it.copy(adding = true) }
            checklistRepository.addItem(taskId, text)
                .onSuccess {
                    _state.updateState { st -> st.copy(newItemText = "", adding = false) }
                }
                .onFailure { err: Throwable ->
                    _state.updateState { st -> st.copy(adding = false, errorMessage = err.message) }
                }
        }
    }

    private fun toggleItem(id: ChecklistItemId) {
        scope.launch {
            checklistRepository.toggleItem(taskId, id)
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
}
