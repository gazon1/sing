package com.singularity.todo.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val userId: Flow<String> = settingsRepository.userId

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TagsUiState> = userId
        .flatMapLatest { uid -> tagRepo.watchTags(uid) }
        .map { tags ->
            if (tags.isEmpty()) TagsUiState.Empty("")
            else TagsUiState.Content(tags)
        }
        .catch { emit(TagsUiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TagsUiState.Loading)

    fun delete(id: TagId) = viewModelScope.launch {
        tagRepo.delete(id)
    }
}
