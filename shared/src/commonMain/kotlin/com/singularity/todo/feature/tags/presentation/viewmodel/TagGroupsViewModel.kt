package com.singularity.todo.feature.tags.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import com.singularity.todo.feature.tags.domain.usecase.CreateTagGroupUseCase
import com.singularity.todo.feature.tags.domain.usecase.DeleteTagGroupUseCase
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ViewModel for the Tag Groups management screen.
 *
 * Watches [TagGroupRepository.observeAll] and maps to [TagGroupsUiState].
 * [TagGroupsIntent.Create] → [CreateTagGroupUseCase]; [TagGroupsIntent.Delete] → [DeleteTagGroupUseCase].
 *
 * @param tagGroupRepo Repository for tag group persistence.
 * @param createTagGroup Use case for creating a new tag group.
 * @param deleteTagGroup Use case for deleting an existing tag group.
 * @param scope CoroutineScope for all coroutine work. Tests pass [AutoCloseableCoroutineScope].
 */
sealed interface TagGroupsUiState {
    data object Loading : TagGroupsUiState
    data object Empty : TagGroupsUiState
    data class Content(val groups: List<TagGroup>) : TagGroupsUiState
    data class Error(val message: String) : TagGroupsUiState
}

sealed interface TagGroupsIntent : MviIntent {
    data class Create(val name: String, val color: Int) : TagGroupsIntent
    data class Delete(val id: TagGroupId) : TagGroupsIntent
}

class TagGroupsViewModel(
    private val tagGroupRepo: TagGroupRepository,
    private val createTagGroup: CreateTagGroupUseCase,
    private val deleteTagGroup: DeleteTagGroupUseCase,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TagGroupsUiState, TagGroupsIntent, Nothing>(
        initialState = TagGroupsUiState.Loading,
        scope = scope,
    ) {

    init {
        addCloseable(scope)
        scope.launch {
            tagGroupRepo.observeAll()
                .map { groups ->
                    if (groups.isEmpty()) {
                        TagGroupsUiState.Empty
                    } else {
                        TagGroupsUiState.Content(groups)
                    }
                }
                .catch {
                    emit(
                        TagGroupsUiState.Error(
                            it.toMessage(),
                        ),
                    )
                }
                .collect { updateState { it } }
        }
    }

    override fun onIntent(intent: TagGroupsIntent) {
        when (intent) {
            is TagGroupsIntent.Create -> scope.launch {
                createTagGroup(CreateTagGroupInput(name = intent.name, color = intent.color))
            }

            is TagGroupsIntent.Delete -> scope.launch {
                deleteTagGroup(intent.id)
            }
        }
    }
}
