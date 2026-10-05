package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
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
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesEditorIntent
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
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.NoteField
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Note editor ViewModel backed by [DraftMviViewModel].
 *
 * Draft type is [Editing] — the editing session including title, body HTML, and
 * isDirty/isNew flags.
 *
 * Autosave uses [NotesRepository.update] via [buildPersistedNote] with 500ms debounce.
 * The builder merges draft changes into the full note read from the DB (or the
 * cached note for opened notes), preserving every field: kind, isPinned, color,
 * taskId, outgoingLinks, wordCount, charCount, etc.
 *
 * Restore returns null since notes are opened by ID, not from draft store.
 *
 * @param repo Note persistence.
 * @param linkRepo For internal link search.
 * @param idGen For generating note IDs on create.
 * @param ai AI improve / action controller.
 * @param log Logger.
 * @param scope Coroutine scope (lifecycle-owned).
 */
internal class NoteEditor(
    private val repo: NotesRepository,
    private val linkRepo: InternalLinkRepository,
    private val idGen: IdGenerator,
    private val ai: NoteAiController,
    private val proposals: ProposalRepository,
    private val applyProposal: ApplyProposalItemUseCase,
    private val log: Logger,
    private val currentUser: ProfileAwareCurrentUser,
    // Stamps the `modified` field of an AI proposal. Read from the system clock it
    // was a value no test could choose, and a proposal's timestamp is exactly the
    // kind of thing a test wants to assert (#91).
    private val clock: Clock,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    // Derived from crashReporter rather than a bare factory call: the autosave lambda below
    // launches on this scope, and a launch whose body throws with no handler escalates to the
    // platform's uncaught-exception handler — on Android, process death.
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
    // Constructor PARAMETER (not body property) so the autosave lambda can capture it.
    // The default is evaluated before the supercall; the lambda body is NOT executed then.
    autosaveCtx: AutosaveContext = AutosaveContext(repo, currentUser, log, scope),
) : DraftMviViewModel<Editing, NotesEditorIntent, NotesUiEvent>(
        crashReporter = crashReporter,
        initialDraft = Editing(id = "", title = "", html = "", isDirty = false, isNew = true),
        // The lambda body is stored (not executed) during default parameter evaluation.
        // It captures autosaveCtx from the constructor parameter scope — no 'this' access.
        autosave = { draft ->
            autosaveCtx.scope.launch {
                val noteId = NoteId.fromString(draft.id)
                val result = if (draft.isNew) {
                    // New note: create it first so we get a proper createdAt + full row.
                    autosaveCtx.repo.createWithContent(
                        id = noteId,
                        title = draft.title,
                        bodyMarkdown = "",
                        bodyHtml = draft.html,
                    )
                } else {
                    // Existing note: targeted UPDATE — preserves all other fields
                    // (isPinned, color, sortOrder, taskId, outgoingLinks, serverVersion, etc.).
                    autosaveCtx.repo.updateContent(
                        id = noteId,
                        title = draft.title,
                        bodyMarkdown = "",
                        bodyHtml = draft.html,
                    )
                }
                result.onFailure { e -> autosaveCtx.log.e(e) { "autosave failed: ${e.message}" } }
            }
        },
        restore = { null },
        logger = log,
        autosaveDebounceMs = 500L,
        scope = scope,
    ) {

    // Capture the parameter as a property for use by createNoteForTask.
    private val autosaveCtx: AutosaveContext = autosaveCtx

    /**
     * All dependencies required by the autosave lambda.
     * [internal] — not part of the public API but accessible within the same package.
     */
    internal data class AutosaveContext(
        val repo: NotesRepository,
        val currentUser: ProfileAwareCurrentUser,
        val log: Logger,
        val scope: AutoCloseableCoroutineScope,
    )

    // Exposed for createNoteForTask which needs to pre-populate the repo before opening.
    val autosaveContext: AutosaveContext get() = autosaveCtx

    /** Caches the existing note when opening to preserve every field across saves. */
    private var cachedNote: Note? = null

    /** Opens an existing note for editing. */
    fun openEditor(noteId: String) {
        vmScope.launch {
            val note = repo.get(NoteId.fromString(noteId))
                ?: return@launch
            cachedNote = note // preserve ALL fields for autosave/persist
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
     * The note is saved to the DB immediately (via autosave) with an empty title;
     * the caller opens the editor which will set isDirty on first keystroke.
     *
     * @param preExistingId When non-null, uses this ID instead of generating a new one.
     *                      Used by [createNoteForTask] to keep the editor ID in sync with
     *                      the note already created in the repository.
     */
    fun createNote(preExistingId: String? = null): String {
        val id = NoteId.fromString(
            preExistingId
                ?: idGen.next(),
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
     * in the DB before autosave. The editor draft uses the same ID.
     * [cachedNote] is preserved so autosave uses [buildPersistedNote] update path.
     */
    suspend fun createNoteForTask(taskId: TaskId) {
        val newNoteId = repo.createForTask(taskId, title = "", bodyMarkdown = null, bodyHtml = null)
            .getOrThrow()
        val note = repo.get(newNoteId)!! // createForTask guarantees the note exists
        cachedNote = note // preserve taskId + createdAt for autosave — DO NOT reset below
        createNote(preExistingId = note.id.value)
    }

    /** Current editor state for the screen. */
    val editorState: DraftUiState<Editing>
        get() = state.value

    override fun validate(draft: Editing): String? = null

    override suspend fun onSaved() {
        // Explicit save: draft is no longer "new" — subsequent saves go through update.
        updateDraft { it.copy(isNew = false) }
        emit(NotesUiEvent.SavedPulse)
    }

    override suspend fun persist(draft: Editing): Either<AppError, Unit> = try {
        // For new notes: createWithContent (INSERT). For existing: updateContent (UPDATE).
        // updateContent is a targeted UPDATE — preserves all other fields:
        // isPinned, pinnedAt, color, sortOrder, kind, isFolder, parentNoteId, taskId,
        // serverVersion, hlc, deletedAt, archivedAt, createdAt.
        val noteId = NoteId.fromString(draft.id)
        if (draft.isNew) {
            autosaveContext.repo.createWithContent(
                id = noteId,
                title = draft.title,
                bodyMarkdown = "",
                bodyHtml = draft.html,
            ).getOrThrow()
        } else {
            autosaveContext.repo.updateContent(
                id = noteId,
                title = draft.title,
                bodyMarkdown = "",
                bodyHtml = draft.html,
            ).getOrThrow()
        }
        // Extract outgoing links from the rendered HTML and persist them.
        // This is the write path for the backlinks feature: without this, outgoing_links
        // is never written and [[note://...]] / [[task://...]] links are dead.
        val linkUrls = NoteContentMapper.outgoingLinkUrls(draft.html)
        autosaveContext.repo.setOutgoingLinks(noteId, linkUrls)
        Either.Right(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Either.Left(AppError.Persistence(e.toMessage(), code = "notes.editor.persist_failed"))
    }

    override fun onAutosaveError(e: Throwable) {
        log.e(e) { "autosave failed: ${e.message}" }
        vmScope.launch {
            emit(
                NotesUiEvent.SaveFailed(
                    e.message
                        ?: "Autosave failed",
                ),
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

        // Note-field mutations (Improve, Summarize, Rewrite) and action-extraction go through
        // proposals. SuggestTags is a read-only event (no direct write to the note).
        when (action) {
            NoteAiAction.Improve,
            NoteAiAction.RewriteOneLiner,
            NoteAiAction.RewriteTldr,
            NoteAiAction.RewriteStructured,
            NoteAiAction.Summarize,
            NoteAiAction.ExtractActions,
            -> runAiThroughProposal(action)

            NoteAiAction.SuggestTags,
            -> runAiThroughEvent(action)
        }
    }

    /**
     * AI actions that produce a note-field change or extract tasks: creates a proposal
     * instead of writing directly.
     */
    private fun runAiThroughProposal(action: NoteAiAction) = vmScope.launch {
        val current = state.value.draft
        if (current.isNew) return@launch

        val noteId = current.id
        val userId = currentUser.scopedUserId.value
        val now = clock.now()

        val result = ai.run(action, current.title, current.html).getOrNull()
        val (kind, summary) = when (action) {
            NoteAiAction.Summarize -> {
                val summary = (result as? SummarizeResult.Ok)?.summary ?: return@launch
                val kind = ProposalItemKind.SetNoteField(NoteField.Summary, summary)
                kind to "Summarize: $summary"
            }

            NoteAiAction.ExtractActions -> {
                val actions = (result as? ExtractActionsResult.Ok)?.actions ?: return@launch
                if (actions.isEmpty()) return@launch
                val kind = ProposalItemKind.ExtractActions(actions)
                kind to "Extract ${actions.size} action(s)"
            }

            NoteAiAction.Improve -> {
                val improved = result as? Improved ?: return@launch
                val kind = ProposalItemKind.SetNoteField(NoteField.Body, improved.body)
                kind to formatNoteAiResult(improved)
            }

            NoteAiAction.RewriteOneLiner,
            NoteAiAction.RewriteTldr,
            NoteAiAction.RewriteStructured,
            -> {
                val improved = result as? Improved ?: return@launch
                val tone = when (action) {
                    NoteAiAction.RewriteOneLiner -> "OneLiner"
                    NoteAiAction.RewriteTldr -> "Tldr"
                    NoteAiAction.RewriteStructured -> "Structured"
                    else -> return@launch
                }
                val kind = ProposalItemKind.SetNoteField(NoteField.Body, improved.body)
                kind to "Rewrite as $tone: ${improved.title}"
            }

            else -> return@launch
        }

        val item = ProposalItem(
            id = com.singularity.todo.core.ids.ProposalItemId.generate(),
            proposalId = com.singularity.todo.core.ids.ProposalId.generate(),
            kind = kind,
            targetId = noteId,
            humanSummary = summary,
            status = ProposalItemStatus.Pending,
            fingerprint = ProposalFingerprint.of(kind, noteId),
            sortOrder = 0,
        )
        val proposal = AiProposal(
            id = item.proposalId,
            targetKind = AiProposal.TARGET_KIND_NOTE,
            targetId = noteId,
            userId = userId,
            source = ProposalSource.Detail,
            status = ProposalStatus.Pending,
            createdAt = now,
            updatedAt = now,
            items = listOf(item),
        )
        proposals.save(proposal).onFailure {
            emit(NotesUiEvent.AiResult("AI error: ${it.message}"))
        }
        // On success, the UI watches proposals and shows the proposal card to the user
    }

    /**
     * AI actions that don't mutate the note directly (read-only): emit through events.
     */
    private fun runAiThroughEvent(action: NoteAiAction) {
        launchDraftEffect(
            key = "ai-$action",
            operation = { current: Editing ->
                ai.run(action, current.title, current.html)
                    .getOrThrow()
            },
            onResult = { _, _ -> null },
            onEvent = { result ->
                @Suppress("UNCHECKED_CAST")
                when (result) {
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

    suspend fun searchNotesForLink(query: String): List<LinkResult> = linkRepo.searchNotes(query)
        .map { LinkResult(it.id.value, it.title, LinkKind.Note) }

    suspend fun searchTasksForLink(query: String): List<LinkResult> = linkRepo.searchTasks(query)
        .map { LinkResult(it.id.value, it.title, LinkKind.Task) }

    // ─── Cleanup ─────────────────────────────────────────────────────────

    fun closeEditor() {
        discard()
    }
}
