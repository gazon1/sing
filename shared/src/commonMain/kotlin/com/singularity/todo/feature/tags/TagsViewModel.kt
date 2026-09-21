package com.singularity.todo.feature.tags

import androidx.lifecycle.ViewModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

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
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        tagRepo: TagsRepository,
    ) : this(
        tagRepo = tagRepo,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TagsUiState> = tagRepo.watchTagsForCurrentUser()
        .map { tags ->
            if (tags.isEmpty()) {
                TagsUiState.Empty("")
            } else {
                TagsUiState.Content(tags)
            }
        }
        .catch { emit(TagsUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), TagsUiState.Loading)

    fun delete(id: TagId) = scope.launch {
        tagRepo.delete(id)
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
