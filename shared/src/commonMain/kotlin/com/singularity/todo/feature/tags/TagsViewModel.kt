package com.singularity.todo.feature.tags

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// --- UI State ---

sealed interface TagsUiState {
    data object Loading : TagsUiState
    data object Empty : TagsUiState
    data class Content(val tags: List<Tag>) : TagsUiState
    data class Error(val message: String) : TagsUiState
}

// --- Intent ---

sealed interface TagsIntent : MviIntent {
    data class Create(val name: String, val color: Int) : TagsIntent
    data class Delete(val id: TagId) : TagsIntent

    /**
     * Renames a tag in place, keeping its id.
     *
     * The id travels in the intent rather than being looked up by name, so
     * every task already linked to the tag keeps that link — a rename must
     * never require re-linking.
     *
     * @param color carried through so that "rename and pick a colour" is one
     *   write instead of a read-modify-write that races a concurrent rename.
     */
    data class Rename(val id: TagId, val name: String, val color: Int) : TagsIntent
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
    private val createTag: CreateTagUseCase,
    private val updateTag: UpdateTagUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
        initialState = TagsUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    init {
        scope.launch {
            tagRepo.observeAll()
                .map { tags ->
                    if (tags.isEmpty()) TagsUiState.Empty else TagsUiState.Content(tags)
                }
                .catch { e ->
                    updateState {
                        TagsUiState.Error(
                            e.toMessage(),
                        )
                    }
                }
                .collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: TagsIntent) {
        when (intent) {
            is TagsIntent.Create -> scope.launch { create(intent.name, intent.color) }
            is TagsIntent.Delete -> scope.launch { delete(intent.id) }
            is TagsIntent.Rename -> scope.launch { rename(intent.id, intent.name, intent.color) }
        }
    }

    /**
     * Fire-and-forget delete. Errors are emitted as [TagsUiEvent.ShowError].
     *
     * Private since [TagsIntent.Delete] routes here: `SettingsScreen` used to take
     * this as a method reference while `onCreate` and `onRename` went through the
     * dispatcher, so the intent handler existed and nothing ever reached it.
     */
    private fun delete(id: TagId) = emitError("Delete failed", TagsUiEvent::ShowError) {
        tagRepo.delete(id)
    }

    /**
     * Renames an existing tag without touching its id.
     *
     * Reads the current row, then hands the copy to [UpdateTagUseCase] — which
     * owns validation and the `updatedAt` stamp.
     *
     * A missing tag is returned as a failed [Result], not thrown: [catchTo]
     * only converts a returned `Result.failure` into a [TagsUiEvent.ShowError],
     * so a thrown [AppError.NotFound] would escape the coroutine and reach the
     * uncaught-exception handler instead of showing an error.
     */
    private suspend fun rename(id: TagId, name: String, color: Int) {
        emitError("Rename tag failed", TagsUiEvent::ShowError) {
            val existing = tagRepo.get(id)
                ?: return@emitError Result.failure<Unit>(
                    AppError.NotFound("Tag $id no longer exists", code = "tag.not_found"),
                )
            updateTag(existing.copy(name = name, color = color))
        }
    }

    private suspend fun create(name: String, color: Int) {
        val userId: UserId = currentUser.scopedUserId.value
        emitError("Create tag failed", TagsUiEvent::ShowError) {
            createTag(CreateTagInput(name = name, color = color, userId = userId))
        }
    }
}
