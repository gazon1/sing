package com.singularity.todo.feature.tags.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tags.domain.usecase.CreateTagGroupUseCase
import com.singularity.todo.feature.tags.domain.usecase.DeleteTagGroupUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TagGroupsUiState {
    data object Loading : TagGroupsUiState
    data class Empty(val userId: String) : TagGroupsUiState
    data class Content(val groups: List<TagGroup>) : TagGroupsUiState
    data class Error(val message: String) : TagGroupsUiState
}

class TagGroupsViewModel(
    private val tagGroupRepo: TagGroupRepository,
    private val createTagGroup: CreateTagGroupUseCase,
    private val deleteTagGroup: DeleteTagGroupUseCase,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init {
        addCloseable(scope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TagGroupsUiState> = tagGroupRepo.observeAll()
        .map { groups ->
            if (groups.isEmpty()) {
                TagGroupsUiState.Empty("")
            } else {
                TagGroupsUiState.Content(groups)
            }
        }
        .catch { emit(TagGroupsUiState.Error(it.message ?: "Error")) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), TagGroupsUiState.Loading)

    fun create(name: String, color: Int) = scope.launch {
        createTagGroup(CreateTagGroupInput(name = name, color = color))
    }

    fun delete(id: TagGroupId) = scope.launch {
        deleteTagGroup(id)
    }
}
