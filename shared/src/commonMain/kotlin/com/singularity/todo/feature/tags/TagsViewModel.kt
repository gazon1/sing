package com.singularity.todo.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
    private val getTags: GetTagsUseCase,
    private val deleteTag: DeleteTagUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val currentUserId = settingsRepository.userIdBlocking()

    val state: StateFlow<TagsUiState> = getTags(currentUserId)
        .map<List<Tag>, TagsUiState> { tags ->
            if (tags.isEmpty()) TagsUiState.Empty(currentUserId)
            else TagsUiState.Content(tags)
        }
        .catch { emit(TagsUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TagsUiState.Loading)

    fun delete(id: TagId) = viewModelScope.launch {
        deleteTag(id)
    }
}
