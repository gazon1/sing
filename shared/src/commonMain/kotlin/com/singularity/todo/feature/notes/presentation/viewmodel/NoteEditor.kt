@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ui.DraftMviViewModel
import com.singularity.todo.core.ui.DraftUiState
import com.singularity.todo.feature.notes.EditorState.Editing
import com.singularity.todo.feature.notes.ExtractActionsResult
import com.singularity.todo.feature.notes.LinkKind
import com.singularity.todo.feature.notes.LinkResult
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteAiAction
import com.singularity.todo.feature.notes.NoteAiResult
import com.singularity.todo.feature.notes.NoteAiResult.Improved
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.SuggestTagsResult
import com.singularity.todo.feature.notes.SummarizeResult
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.notes.formatExtractActionsResult
import com.singularity.todo.feature.notes.formatNoteAiResult
import com.singularity.todo.feature.notes.formatSuggestTagsResult
import com.singularity.todo.feature.notes.formatSummarizeResult
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Note editor ViewModel backed by [DraftMviViewModel].
 *
 * Draft type is [Editing] — the editing session including title, body HTML, and
 * isDirty/isNew flags.
 *
 * Autosave uses [NotesRepository.upsert] with 500ms debounce.
 * Restore returns null since notes are opened by ID, not from draft store.
 *
 * @param repo Note persistence.
 * @param linkRepo For internal link search.
 * @param idGen For generating note IDs on create.
 * @param ai AI improve / action controller.
 * @param log Logger.
 * @param scope Coroutine scope (lifecycle-owned).
 */
class NoteEditor(
    private val repo: NotesRepository,
    private val linkRepo: InternalLinkRepository,
    private val idGen: IdGenerator,
    private val ai: NoteAiController,
    private val log: Logger,
    private val currentUser: ProfileAwareCurrentUser,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : DraftMviViewModel<Editing, NotesEditorIntent, NotesUiEvent>(
    initialDraft = Editing(id = "", title = "", html = "", isDirty = false, isNew = true),
    autosave = { draft ->
        // Autosave reads from DB to preserve createdAt. Debounced to 500ms, so the
        // extra round-trip on every keystroke is acceptable.
        // Note: taskId is NOT preserved here — it is only set via editingAsNote
        // (the explicit-save path) which is guaranteed to have cachedNote set.
        val noteId = NoteId.fromString(draft.id)
        val existing = repo.get(noteId)
        val now: kotlin.time.Instant = Clock.System.now()
        repo.upsert(
            Note(
                id = noteId,
                userId = currentUser.scopedUserId.value,
                title = draft.title,
                bodyHtml = draft.html,
                bodyMarkdown = null,
                createdAt = existing?.createdAt
                    ?: now,
                updatedAt = now,
                isFolder = false,
                // taskId is preserved via cachedNote in the explicit-save path only
            ),
        )
    },
    restore = { null },
    logger = log,
    autosaveDebounceMs = 500L,
    scope = scope,
) {

    /** Caches the existing note when opening to preserve createdAt across saves. */
    private var cachedNote: Note? = null

    /** Converts an Editing draft to Note using the cached note to preserve createdAt. */
    private fun editingAsNote(draft: Editing): Note {
        val noteId = NoteId.fromString(draft.id)
        val existing = cachedNote
        val now: kotlin.time.Instant = Clock.System.now()
        return Note(
            id = noteId,
            userId = currentUser.scopedUserId.value,
            title = draft.title,
            bodyHtml = draft.html,
            bodyMarkdown = null,
            createdAt = existing?.createdAt
                ?: now,
            updatedAt = now,
            isFolder = false,
        )
    }

    /** Opens an existing note for editing. */
    fun openEditor(noteId: String) {
        vmScope.launch {
            val note = repo.get(NoteId.fromString(noteId))
                ?: return@launch
            cachedNote = note // cache for autosave to preserve createdAt
            val html = note.bodyHtml
                ?: note.bodyMarkdown?.let { NoteContentMapper.toHtml(it) }
                ?: ""
            val draft = Editing(
                id = note.id.value,
                title = note.title,
                html = html,
                isDirty = false,
                isNew = false,
            )
            // open() FIRST: it advances baseline to the opened note. Calling
            // updateDraft first would make open() a no-op (sameEntity early-return),
            // leaving baseline at the empty initial draft — closeEditor (discard)
            // would then revert to an empty editor instead of the opened note.
            open(draft)
            updateDraft { draft }
        }
    }

    /**
     * Creates a new note and returns the generated ID.
     * @param preExistingId When non-null, uses this ID instead of generating a new one.
     *                      Used by [createNoteForTask] to keep the editor ID in sync with
     *                      the note already created in the repository.
     */
    fun createNote(preExistingId: String? = null): String {
        cachedNote = null // no existing note for new notes
        val id = NoteId.fromString(
            preExistingId
                ?: idGen.next()
        )
        val draft = Editing(
            id = id.value,
            title = "",
            html = "",
            isDirty = false,
            isNew = true,
        )
        open(draft)
        updateDraft { draft }
        return id.value
    }

    /**
     * Creates a new note attached to [taskId] and opens it in the editor.
     * The note is first persisted via [NotesRepository.createForTask] so it exists
     * in the DB before autosave triggers the first `upsert`. The editor draft uses
     * the same ID so subsequent autosave updates the already-created note.
     */
    suspend fun createNoteForTask(taskId: TaskId) {
        val newNoteId = repo.createForTask(taskId, title = "", bodyMarkdown = null, bodyHtml = null)
            .getOrThrow() // propagate failure via exception
        val note = repo.get(newNoteId)!! // createForTask guarantees the note exists
        cachedNote = note // preserve taskId + createdAt for autosave
        createNote(preExistingId = note.id.value)
    }

    /** Current editor state for the screen. */
    val editorState: DraftUiState<Editing>
        get() = state.value

    override fun validate(draft: Editing): String? =
        null

    override suspend fun onSaved() {
        // Explicit save persists the note: it is no longer "new" — a subsequent
        // save must go through update, not create.
        updateDraft { it.copy(isNew = false) }
        emit(NotesUiEvent.SavedPulse)
    }

    override suspend fun persist(draft: Editing): Either<AppError, Unit> =
        try {
            repo.upsert(editingAsNote(draft))
            Either.Right(Unit)
        } catch (e: Exception) {
            Either.Left(AppError.Persistence(e.toMessage()))
        }

    override fun onAutosaveError(e: Throwable) {
        log.e(e) { "autosave failed: ${e.message}" }
        vmScope.launch {
            emit(
                NotesUiEvent.SaveFailed(
                    e.message
                        ?: "Autosave failed"
                )
            )
        }
    }

    override fun onAutosaved(current: Editing) {
        // The autosaved content is persisted: clear the draft-local dirty flag
        // (mirroring the derived DraftUiState.isDirty which the base clears via
        // baseline) and the isNew flag — the note now exists in the repo, so a
        // subsequent save is an update, not a create.
        updateDraft { it.copy(isDirty = false, isNew = false) }
    }

    override fun onIntent(intent: NotesEditorIntent) {
        when (intent) {
            is NotesEditorIntent.OpenNote -> openEditor(intent.noteId)
            is NotesEditorIntent.CreateNote -> createNote()
            is NotesEditorIntent.EditTitle -> updateDraft { it.copy(title = intent.title, isDirty = true) }
            is NotesEditorIntent.EditBody -> updateDraft { it.copy(html = intent.html, isDirty = true) }
            NotesEditorIntent.SaveNow -> save()
            NotesEditorIntent.Close -> closeEditor()
            is NotesEditorIntent.RunAiAction -> runAiAction(intent.action)
            NotesEditorIntent.DismissError -> dismissError()
        }
    }

    // ─── AI actions via launchDraftEffect ───────────────────────────────────

    private fun runAiAction(action: NoteAiAction) {
        if (!ai.isActionAvailable(action)) return
        launchDraftEffect(
            key = "ai-$action",
            operation = { current: Editing ->
                ai.run(action, current.title, current.html)
                    .getOrThrow()
            },
            onResult = { before, success ->
                @Suppress("UNCHECKED_CAST") when (success) {
                    is Improved -> before.copy(title = success.title, html = success.body, isDirty = true)
                    else -> null
                }
            },
            onEvent = { result ->
                @Suppress("UNCHECKED_CAST") when (result) {
                    is Improved -> NotesUiEvent.AiResult(formatNoteAiResult(result))
                    is SummarizeResult -> NotesUiEvent.AiResult(formatSummarizeResult(result))
                    is ExtractActionsResult -> NotesUiEvent.AiResult(formatExtractActionsResult(result))
                    is SuggestTagsResult -> NotesUiEvent.AiResult(formatSuggestTagsResult(result))
                    is NoteAiResult.Error -> NotesUiEvent.AiResult("AI error: ${result.message}")
                    else -> null
                }
            },
        )
    }

    // ─── Link search ─────────────────────────────────────────────────────

    suspend fun searchNotesForLink(query: String): List<LinkResult> =
        linkRepo.searchNotes(query)
            .map { LinkResult(it.id.value, it.title, LinkKind.Note) }

    suspend fun searchTasksForLink(query: String): List<LinkResult> =
        linkRepo.searchTasks(query)
            .map { LinkResult(it.id.value, it.title, LinkKind.Task) }

    // ─── Cleanup ─────────────────────────────────────────────────────────

    fun closeEditor() {
        discard()
    }
}
