package com.singularity.todo.feature.tags

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.mvi.MviEvent
import com.singularity.todo.core.ui.mvi.MviIntent
import com.singularity.todo.core.ui.mvi.MviViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// --- UI State ---

sealed interface TagsUiState {
    data object Loading : TagsUiState
    data class Empty(val userId: String) : TagsUiState
    data class Content(val tags: List<Tag>) : TagsUiState
    data class Error(val message: String) : TagsUiState
}

// --- Intent ---

sealed interface TagsIntent : MviIntent {
    data class Delete(val id: TagId) : TagsIntent
}

// --- Events (one-shot, for async operations only) ---

sealed interface TagsUiEvent : MviEvent {
    data class ShowError(val message: String) : TagsUiEvent
}

/**
 * Tags list screen ViewModel.
 *
 * Collects tag list via [tagRepo.observeAll].
 * Delete failures emit [TagsUiEvent.ShowError] as one-shot events.
 * Collection errors are mapped to [TagsUiState.Error].
 *
 * @see TagsUiState
 * @see TagsIntent
 * @see TagsUiEvent
 */
class TagsViewModel(
    private val tagRepo: TagsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
        initialState = TagsUiState.Loading,
        scope = scope,
    ) {

    init {
        addCloseable(scope)
        scope.launch {
            tagRepo.observeAll()
                .map { tags ->
                    if (tags.isEmpty()) TagsUiState.Empty("") else TagsUiState.Content(tags)
                }
                .catch { e -> updateState { TagsUiState.Error(e.message ?: "Error") } }
                .collect { newState -> update { newState } }
        }
    }

    override fun onIntent(intent: TagsIntent) {
        when (intent) {
            is TagsIntent.Delete -> scope.launch { delete(intent.id) }
        }
    }

    /**
     * Fire-and-forget delete. Errors are emitted as [TagsUiEvent.ShowError].
     * Exposed as a method reference for Compose UI callbacks (see [SettingsScreen]).
     */
    fun delete(id: TagId) = scope.launch {
        tagRepo.delete(id)
            .onFailure { emit(TagsUiEvent.ShowError(it.message ?: "Error")) }
    }
}
