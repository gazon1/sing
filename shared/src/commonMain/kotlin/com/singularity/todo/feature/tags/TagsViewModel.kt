package com.singularity.todo.feature.tags

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.components.CountdownStateMachine
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.domain.usecase.CreateTagUseCase
import com.singularity.todo.feature.tags.domain.usecase.UpdateTagUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** User tapped Undo on the delete snackbar. */
    data object UndoDeleteTapped : TagsIntent
}

// --- Events (one-shot, for async operations only) ---

sealed interface TagsUiEvent : MviEvent {
    data class ShowError(val message: String) : TagsUiEvent

    /**
     * A delete was performed and the user can undo it for [UNDO_WINDOW_MS].
     * @param tagId The deleted tag id.
     * @param title Short label for the snackbar.
     */
    data class UndoDelete(val tagId: TagId, val title: String) : TagsUiEvent
}

/**
 * Marks a tag deletion that can still be undone.
 *
 * @param tagId The deleted tag id.
 * @param title Short label for the snackbar.
 */
data class PendingTagDelete(val tagId: TagId, val title: String)

/**
 * Tags list screen ViewModel.
 *
 * Collects tag list via [tagRepo.observeAll].
 * Delete failures emit [TagsUiEvent.ShowError] as one-shot events.
 * Collection errors are mapped to [TagsUiState.Error].
 * Delete success emits [TagsUiEvent.UndoDelete] and starts the undo window.
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
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
        initialState = TagsUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    companion object {
        /** 5-second undo window, matching the snackbar countdown. */
        private const val UNDO_WINDOW_MS = 5_000L

        private const val DELETE_FAILED = "tags.delete_failed"
        private const val RESTORE_FAILED = "tags.restore_failed"
    }

    // ── Undo-delete state ────────────────────────────────────────────────────────

    /**
     * The tag a delete is holding open for undo, or null when none is.
     *
     * Public so the screen drives the snackbar directly from it; cleared
     * when the window expires or the reversal succeeds.
     */
    private val _pendingDelete = MutableStateFlow<PendingTagDelete?>(null)
    val pendingDelete = _pendingDelete.asStateFlow()

    /** Drives the undo snackbar countdown. Uses [UNDO_WINDOW_MS] and tick counting for virtual-time test compatibility. */
    private val countdown = CountdownStateMachine(
        scope = scope,
        windowMs = UNDO_WINDOW_MS,
        onExpired = { _pendingDelete.value = null },
    )
    /** Exposes countdown progress to the screen's snackbar progress bar. */
    val countdownProgress: StateFlow<Float?> = countdown.progress

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
            is TagsIntent.Delete -> scope.launch { handleDelete(intent.id) }
            is TagsIntent.Rename -> scope.launch { rename(intent.id, intent.name, intent.color) }
            is TagsIntent.UndoDeleteTapped -> scope.launch { handleUndoDeleteTapped() }
        }
    }

    /**
     * Soft-deletes a tag, then offers [TagsUiEvent.UndoDelete] for [UNDO_WINDOW_MS].
     *
     * The deletion is performed first; the undo offer is made only on success.
     * A failed delete never evicts a recoverable undo.
     */
    private suspend fun handleDelete(id: TagId) {
        // Read title before deleting so we still have the row.
        val tagTitle = tagRepo.get(id)?.name ?: id.value

        val deleted = tagRepo.delete(id)
        if (deleted.isFailure) {
            val error = deleted.exceptionOrNull() ?: IllegalStateException("delete failed")
            crashReporter.report(error, DELETE_FAILED)
            emit(TagsUiEvent.ShowError("Delete failed"))
            return
        }

        _pendingDelete.value = PendingTagDelete(id, tagTitle)
        emit(TagsUiEvent.UndoDelete(id, tagTitle))
        countdown.start { _pendingDelete.value?.tagId == id }
    }

    /**
     * Restores the tag held by [_pendingDelete], clearing the offer on success only.
     * A failed reversal leaves the offer standing so the user can retry.
     */
    private suspend fun handleUndoDeleteTapped() {
        val pending = _pendingDelete.value ?: return
        tagRepo.restore(pending.tagId)
            .onSuccess {
                countdown.cancel()
                _pendingDelete.value = null
            }
            .onFailure {
                crashReporter.report(it, RESTORE_FAILED)
            }
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
