package com.singularity.todo.feature.tags.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
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
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TagGroupsUiState, TagGroupsIntent, Nothing>(
        initialState = TagGroupsUiState.Loading,
        crashReporter = crashReporter,
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
                .catch { error ->
                    // A flow that stops emitting is a defect, not a user-actionable error, so
                    // it goes to the reporter and the screen shows the message. Reported from
                    // here rather than through catchTo: this is a Flow operator, not a suspend
                    // block that yields a Result.
                    crashReporter.report(error, TAG_GROUPS_OBSERVE_FAILED)
                    emit(
                        TagGroupsUiState.Error(
                            error.toMessage(),
                        ),
                    )
                }
                .collect { content -> setState(content) }
        }
    }

    override fun onIntent(intent: TagGroupsIntent) {
        when (intent) {
            // These used to launch and discard the Result: a failed create or delete left the
            // screen looking unchanged, with no message and nothing in the report.
            is TagGroupsIntent.Create -> catchTo(
                "Failed to create tag group",
                { msg -> setState(TagGroupsUiState.Error(msg)) },
            ) {
                createTagGroup(CreateTagGroupInput(name = intent.name, color = intent.color))
            }

            is TagGroupsIntent.Delete -> catchTo(
                "Failed to delete tag group",
                { msg -> setState(TagGroupsUiState.Error(msg)) },
            ) {
                deleteTagGroup(intent.id)
            }
        }
    }

    private companion object {
        /** Machine-shaped grouping key — it leaves the device. */
        const val TAG_GROUPS_OBSERVE_FAILED = "tag_groups.observe_failed"
    }
}
