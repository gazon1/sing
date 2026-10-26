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
import com.singularity.todo.core.search.SearchMode
import com.singularity.todo.core.ui.DraftMviViewModel
import com.singularity.todo.core.ui.DraftUiState
import com.singularity.todo.core.text.Revisioned
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
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
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
 * Writes go through one writer — [writeNote] — used by both the debounced autosave and
 * the explicit Save. A note is *created* once, with the whole row (including the task
 * attachment, when the note was opened from a task's logbook), and every later save is a
 * targeted UPDATE that touches the content columns only. That is what keeps an attached
 * note in its task's logbook: a content save cannot drop the attachment.
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
    private val log: Logger,
    private val currentUser: ProfileAwareCurrentUser,
    // Stamps the `modified` field of an AI proposal. Read from the system clock it
    // was a value no test could choose, and a proposal's timestamp is exactly the
    // kind of thing a test wants to assert (#91).
    private val clock: Clock,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    // Derived from crashReporter rather than a bare factory call: a coroutine launched on
    // this scope whose body throws with no handler escalates to the platform's
    // uncaught-exception handler — on Android, process death.
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
    // Constructor PARAMETER (not body property) so the autosave lambda can capture it.
    // The default is evaluated before the supercall; the lambda body is NOT executed then.
    autosaveCtx: AutosaveContext = AutosaveContext(repo, currentUser, log),
) : DraftMviViewModel<Editing, NotesEditorIntent, NotesUiEvent>(
        crashReporter = crashReporter,
        initialDraft = Editing(
            id = "",
            title = Revisioned.initial(""),
            html = Revisioned.initial(""),
            isDirty = false,
            isNew = true,
        ),
        // The lambda body is stored (not executed) during default parameter evaluation.
        // It captures autosaveCtx from the constructor parameter scope — no 'this' access.
        autosave = { draft ->
            // An untouched draft is not a note yet. Writing it here would leave an
            // empty row behind for a user who opened the editor and left — the same
            // kind of leftover the task logbook used to collect.
            if (draft.isNew && !draft.isDirty) {
                Unit
            } else {
                // The write completes before the lambda returns, so the base class
                // learns the outcome: a failed autosave reaches the user instead of
                // the log.
                when (val outcome = autosaveCtx.writeNote(draft)) {
                    is Either.Left -> throw outcome.error
                    is Either.Right -> Unit
                }
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
    )

    /** The writer's dependencies, for the code paths that write outside the autosave lambda. */
    val autosaveContext: AutosaveContext get() = autosaveCtx

    /** Watches the open note for rewrites that come from outside this editor. */
    private val rewriteWatcher = NoteRewriteWatcher(repo, vmScope)

    /** Opens an existing note for editing. */
    fun openEditor(noteId: String) {
        vmScope.launch {
            val note = repo.get(NoteId.fromString(noteId))
                ?: return@launch
            val html = note.bodyHtml
                ?: note.bodyMarkdown?.let { NoteContentMapper.toHtml(it) }
                ?: ""
            // Named `opened`, not `draft`: a local named `draft` would shadow the
            // ViewModel's live draft inside the watcher's lambdas, and the
            // watcher must see the *live* draft — otherwise it compares the note
            // against the snapshot it was opened with and reports rewrites for
            // content the user has since changed.
            val opened = Editing(
                id = note.id.value,
                title = Revisioned.initial(note.title),
                html = Revisioned.initial(html),
                isDirty = false,
                isNew = false,
            )
            // open() FIRST: it advances baseline to the opened note. Calling
            // updateDraft first would make open() a no-op (sameEntity early-return),
            // leaving baseline at the empty initial draft — closeEditor (discard)
            // would then revert to an empty editor instead of the opened note.
            open(opened)
            updateDraft { opened }
            rewriteWatcher.start(NoteId.fromString(noteId), { draft }) { note, html ->
                val reseeded = draft.copy(
                    // Each field's count moves for its own field. A rewrite that changed
                    // only the body must not announce itself through the title's count:
                    // the title was not replaced, and the editor's title field must not
                    // be told that it was.
                    title = draft.title.replacedIfChangedBy(note.title),
                    html = draft.html.replacedIfChangedBy(html),
                    isDirty = false,
                    isNew = false,
                )
                open(reseeded)
                updateDraft { reseeded }
            }
        }
    }

    /**
     * Creates a new note and returns the generated ID.
     *
     * Nothing is written yet: the note reaches the database with the first save, which
     * writes it whole — with the id generated here, so the draft, the navigation and the
     * written row all speak about the same note.
     */
    fun createNote(): String {
        val id = NoteId.fromString(idGen.next())
        val draft = Editing(
            id = id.value,
            title = Revisioned.initial(""),
            html = Revisioned.initial(""),
            isDirty = false,
            isNew = true,
        )
        open(draft)
        updateDraft { draft }
        return id.value
    }

    /**
     * Opens an empty note that belongs to [taskId].
     *
     * Nothing is written yet — the note is created, already attached, by the first save.
     * The attachment lives in the draft until then, which is what makes the note show up
     * in that task's logbook.
     */
    fun createNoteForTask(taskId: TaskId) {
        createNote()
        updateDraft { it.copy(taskId = taskId) }
    }

    /** Current editor state for the screen. */
    val editorState: DraftUiState<Editing>
        get() = _state.value

    override fun validate(draft: Editing): String? = null

    override suspend fun onSaved() {
        // Explicit save: draft is no longer "new" — subsequent saves go through update.
        updateDraft { it.copy(isNew = false) }
        emit(NotesUiEvent.SavedPulse)
    }

    override suspend fun persist(draft: Editing): Either<AppError, Unit> = autosaveContext.writeNote(draft)

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
        // Nothing was written — the draft was untouched, so it stays "new" and keeps its
        // task attachment until the user writes something or saves.
        if (current.isNew && !current.isDirty) return
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
            is NotesEditorIntent.EditTitle -> updateDraft { it.withTitle(intent.title) }
            is NotesEditorIntent.EditBody -> updateDraft { it.withBody(intent.html) }
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
        val current = _state.value.draft
        if (current.isNew) return@launch

        val noteId = current.id
        val userId = currentUser.scopedUserId.value
        val now = clock.now()

        val result = ai.run(action, current.title.value, current.html.value).getOrNull()
        val (kind, summary) = when (action) {
            NoteAiAction.Summarize -> {
                val summary = (result as? SummarizeResult.Ok)?.summary ?: return@launch
                val kind = ProposalItemKind.SetNoteField(NoteValue.Summary(summary))
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
                val kind = ProposalItemKind.SetNoteField(NoteValue.Body(improved.body))
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
                }
                val kind = ProposalItemKind.SetNoteField(NoteValue.Body(improved.body))
                kind to "Rewrite as $tone: ${improved.title}"
            }

            else -> return@launch
        }

        val item = ProposalItem.pending(
            id = com.singularity.todo.core.ids.ProposalItemId.generate(),
            proposalId = com.singularity.todo.core.ids.ProposalId.generate(),
            kind = kind,
            targetId = noteId,
            humanSummary = summary,
            fingerprint = ProposalFingerprint.of(kind, noteId),
        )
        val proposal = AiProposal(
            id = item.proposalId,
            targetKind = kind.target.targetKind,
            targetId = noteId,
            userId = userId,
            source = ProposalSource.Detail,
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
                ai.run(action, current.title.value, current.html.value)
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

    /**
     * The notes offered for a link, while the person types.
     *
     * The editor states the mode it searches in (F8): the word mode — the
     * suggestion list is asked of the index on every keystroke, and the index
     * answers the beginning of a word (F6).
     */
    suspend fun searchNotesForLink(query: String): List<LinkResult> =
        linkRepo.searchNotes(query, LINK_SEARCH_MODE)
            .map { LinkResult(it.id.value, it.title, LinkKind.Note) }

    /** The tasks offered for a link. See [searchNotesForLink]. */
    suspend fun searchTasksForLink(query: String): List<LinkResult> =
        linkRepo.searchTasks(query, LINK_SEARCH_MODE)
            .map { LinkResult(it.id.value, it.title, LinkKind.Task) }

    // ─── Cleanup ─────────────────────────────────────────────────────────

    fun closeEditor() {
        // The watcher must not re-seed an editor that is on its way out.
        rewriteWatcher.stop()
        discard()
    }

    private companion object {
        /**
         * The mode the link suggestions are searched in.
         *
         * The editor is a surface, and a surface states the mode it searches in
         * (F8). The word mode is the one it states: the list is asked of the
         * index on every keystroke, and the index answers the beginning of a
         * word (F6).
         */
        val LINK_SEARCH_MODE: SearchMode = SearchMode.Word
    }
}

/**
 * The one writer of the note editor.
 *
 * A note is *created* once — with the whole row, including the task attachment when the
 * note was opened from a task's logbook — and every later save is a targeted UPDATE that
 * touches the content columns only. A content save therefore cannot drop the attachment,
 * which is what keeps an attached note in its task's logbook.
 *
 * The debounced autosave and the explicit Save both come here, so there is one place
 * where "what a save writes" is decided. It is a file-level extension because the
 * autosave lambda is a constructor argument: it cannot reach `this` before the
 * superclass call.
 */
internal suspend fun NoteEditor.AutosaveContext.writeNote(draft: Editing): Either<AppError, Unit> {
    val noteId = NoteId.fromString(draft.id)
    // The one writer. A new note is *created* — already attached to its task, if it
    // came from a logbook — and every later save is a targeted UPDATE that touches
    // the content columns only, so it cannot detach the note again.
    val written = if (draft.isNew) {
        val taskId = draft.taskId
        if (taskId != null) {
            repo.createForTask(
                id = noteId,
                taskId = taskId,
                title = draft.title.value,
                bodyMarkdown = "",
                bodyHtml = draft.html.value,
            )
        } else {
            repo.createWithContent(
                id = noteId,
                title = draft.title.value,
                bodyMarkdown = "",
                bodyHtml = draft.html.value,
            )
        }
    } else {
        repo.updateContent(
            id = noteId,
            title = draft.title.value,
            bodyMarkdown = "",
            bodyHtml = draft.html.value,
        )
    }
    // The repository reports a failure as a failed Result — it does not throw — so
    // the failure becomes a Left here instead of an exception that would be lost.
    val writeFailure = written.exceptionOrNull()
    if (writeFailure != null) {
        return Either.Left(AppError.Persistence(writeFailure.toMessage(), code = "notes.editor.persist_failed"))
    }
    // Extract outgoing links from the rendered HTML and persist them.
    // This is the write path for the backlinks feature: without this, outgoing_links
    // is never written and [[note://...]] / [[task://...]] links are dead.
    //
    // Reported the same way as the write above, and for the same reason: without it
    // a save reported success while the backlinks feature silently did nothing,
    // which is the exact failure this comment claims to prevent. Outgoing links are
    // not synced — no DocType describes them, see SyncedWriteEnqueuesTest — so a
    // failure here loses the column locally and there is no later reconciliation
    // that would notice.
    val linkUrls = NoteContentMapper.outgoingLinkUrls(draft.html.value)
    val linkFailure = repo.setOutgoingLinks(noteId, linkUrls).exceptionOrNull()
    if (linkFailure != null) {
        return Either.Left(AppError.Persistence(linkFailure.toMessage(), code = "notes.editor.persist_failed"))
    }
    return Either.Right(Unit)
}
