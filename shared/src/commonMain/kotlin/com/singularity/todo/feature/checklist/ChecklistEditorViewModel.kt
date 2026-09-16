package com.singularity.todo.feature.checklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class ChecklistEditorViewModel(private val checklistUseCase: ChecklistUseCase) : ViewModel() {

    private val _state = MutableStateFlow(ChecklistEditorState())
    val state: StateFlow<ChecklistEditorState> = _state.asStateFlow()

    fun bindToTask(taskId: String) {
        _state.update { it.copy(taskId = taskId) }
        viewModelScope.launch {
            checklistUseCase.watchChecklist(taskId).collect { items ->
                _state.update { st ->
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
            is ChecklistEditorIntent.NewItemTextChanged -> _state.update { it.copy(newItemText = intent.text) }
            ChecklistEditorIntent.AddItem -> addItem()
            is ChecklistEditorIntent.ToggleItem -> toggleItem(intent.id)
            is ChecklistEditorIntent.DeleteItem -> deleteItem(intent.id)
            ChecklistEditorIntent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private fun addItem() {
        val text = _state.value.newItemText.trim()
        if (text.isBlank()) return
        val taskId = _state.value.taskId
        if (taskId.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(adding = true) }
            checklistUseCase.addItem(taskId, text)
                .onSuccess {
                    _state.update { st -> st.copy(newItemText = "", adding = false) }
                }
                .onFailure { err: Throwable ->
                    _state.update { st -> st.copy(adding = false, errorMessage = err.message) }
                }
        }
    }

    private fun toggleItem(id: ChecklistItemId) {
        val item = _state.value.items.find { it.id == id } ?: return
        val taskId = _state.value.taskId

        viewModelScope.launch {
            checklistUseCase.toggleItem(id, item.title, taskId, item.isCompleted)
                .onFailure { err: Throwable ->
                    _state.update { st -> st.copy(errorMessage = err.message) }
                }
        }
    }

    private fun deleteItem(id: ChecklistItemId) {
        viewModelScope.launch {
            checklistUseCase.deleteItem(id)
                .onFailure { err: Throwable ->
                    _state.update { st -> st.copy(errorMessage = err.message) }
                }
        }
    }
}
