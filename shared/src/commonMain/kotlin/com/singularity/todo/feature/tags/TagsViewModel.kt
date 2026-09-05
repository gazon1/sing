package com.singularity.todo.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.feature.tags.usecase.DeleteTagUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TagsUiState {
    data object Loading : TagsUiState
    data class Empty(val userId: String) : TagsUiState
    data class Content(val tags: List<Tag>) : TagsUiState
    data class Error(val message: String) : TagsUiState
}

class TagsViewModel(
    private val tagRepo: TagsRepository,
    private val currentUser: CurrentUser,
    private val deleteTag: DeleteTagUseCase,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val userIdFlow = currentUser.userId

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TagsUiState> = userIdFlow
        .flatMapLatest { uid -> tagRepo.watchTags(uid.value) }
        .map { tags ->
            if (tags.isEmpty()) TagsUiState.Empty("")
            else TagsUiState.Content(tags)
        }
        .catch { emit(TagsUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), TagsUiState.Loading)

    fun delete(id: TagId) = scope.launch {
        deleteTag(id)
    }
}
