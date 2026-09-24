package com.singularity.todo.feature.tags

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface TagsUiState {
    data object Loading : TagsUiState
    data class Empty(val userId: String) : TagsUiState
    data class Content(val tags: List<Tag>) : TagsUiState
    data class Error(val message: String) : TagsUiState
}

/**
 * Tags list screen ViewModel.
 *
 * Owns: tag list filtered to current user.
 * Triggers: tag create/delete.
 * One-shot events: [TagUiEvent.ShowError].
 *
 * @see TagsUiState
 */
class TagsViewModel(
    private val tagRepo: TagsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    private val _state = MutableStateFlow<TagsUiState>(TagsUiState.Loading)
    val state: StateFlow<TagsUiState> = _state.asStateFlow()

    init {
        addCloseable(scope)
        scope.launch {
            tagRepo.observeAll()
                .map { tags ->
                    if (tags.isEmpty()) {
                        TagsUiState.Empty("")
                    } else {
                        TagsUiState.Content(tags)
                    }
                }
                .catch { emit(TagsUiState.Error(it.message ?: "Error")) }
                .collect { _state.value = it }
        }
    }

    fun delete(id: TagId) = scope.launch {
        tagRepo.delete(id)
    }
}
