package com.singularity.todo.feature.notes.presentation.viewmodel

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.ExtractActionsResult
import com.singularity.todo.feature.notes.LinkKind
import com.singularity.todo.feature.notes.LinkResult
import com.singularity.todo.feature.notes.NoteAiAction
import com.singularity.todo.feature.notes.NoteAiResult
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.SummarizeResult
import com.singularity.todo.feature.notes.SuggestTagsResult
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.notes.domain.editor.NoteEditorState
import com.singularity.todo.feature.notes.domain.editor.NoteSaver
import com.singularity.todo.feature.notes.formatExtractActionsResult
import com.singularity.todo.feature.notes.formatNoteAiResult
import com.singularity.todo.feature.notes.formatSummarizeResult
import com.singularity.todo.feature.notes.formatSuggestTagsResult
import com.singularity.todo.feature.search.InternalLinkRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the note editing session: title, body, autosave, AI improve.
 *
 * Public API is unchanged from the previous version — [NoteEditorScreen]
 * subscribes to [editorState], [savedPulse], [events] and calls [openEditor] /
 * [createNote] / [editTitle] / [editBody] / [saveNow] / [improveNote] /
 * [searchNotesForLink] / [searchTasksForLink] / [closeEditor].
 *
 * Internal helpers:
 * - [NoteEditorState][com.singularity.todo.feature.notes.domain.editor.NoteEditorState] —
 *   holds EditorState + dirty/new tracking
 * - [NoteSaver][com.singularity.todo.feature.notes.domain.editor.NoteSaver] —
 *   persists content + outgoing links, handles errors and pulse
 * - [NoteContentMapper][com.singularity.todo.feature.notes.domain.NoteContentMapper] —
 *   pure HTML↔Markdown and link extraction
 * - [NoteAiController][com.singularity.todo.feature.notes.domain.editor.NoteAiController] —
 *   wraps the AI improve use case
 */
class NoteEditor(
    private val repo: NotesRepository,
    private val linkRepo: InternalLinkRepository,
    private val idGen: IdGenerator,
    private val ai: NoteAiController,
    private val log: Logger,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {



    // ─── State ─────────────────────────────────────────────────────────────

    private val state = NoteEditorState()
    val editorState: StateFlow<EditorState> = state.state

    /** One-shot "Saved" pulse — triggers the Saved-pill animation in the UI. */
    private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

    /** One-shot UI events (errors, navigation). */
    private val _events = Channel<NotesUiEvent>(Channel.BUFFERED)
    val events: Flow<NotesUiEvent> = _events.receiveAsFlow()

    private val saver = NoteSaver(repo, log, _events, _savedPulse)
    private var autosaveJob: Job? = null

    // ─── Open / create ─────────────────────────────────────────────────────

    /**
     * Opens an existing note for editing.
     * If the editor already has the same note id loaded, this is a no-op —
     * in-memory edits are preserved even if the underlying DB row hasn't saved yet.
     */
    fun openEditor(noteId: String) = scope.launch {
        if (state.current?.id == noteId) return@launch
        val note = repo.get(NoteId.fromString(noteId)) ?: return@launch
        val html = note.bodyHtml
            ?: note.bodyMarkdown?.let { NoteContentMapper.toHtml(it) }
            ?: ""
        state.open(
            EditorState.Editing(
                id = note.id.value,
                title = note.title,
                html = html,
                isDirty = false,
            ),
        )
    }

    /**
     * create-on-first-save: generates an id and opens the editing state marked `isNew`.
     * The note is persisted to the DB only on the first successful save.
     * Returns the new note id so the caller can navigate immediately.
     */
    fun createNote(): String {
        val id = NoteId.fromString(idGen.next())
        state.open(
            EditorState.Editing(
                id = id.value,
                title = "",
                html = "",
                isDirty = false,
                isNew = true,
            ),
        )
        return id.value
    }

    // ─── Edit + autosave ──────────────────────────────────────────────────

    fun editTitle(title: String) {
        state.updateTitle(title)
        scheduleAutosave()
    }

    fun editBody(html: String) {
        state.updateHtml(html)
        scheduleAutosave()
    }

    /** Immediate save — cancels the pending autosave and triggers an immediate one. */
    fun saveNow() = scheduleAutosave(ZERO_DELAY)

    private fun scheduleAutosave(delay: Duration = AUTOSAVE_DEBOUNCE) {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(delay)
            val c = state.current ?: return@launch
            if (saver.save(NoteId.fromString(c.id), c.title, c.html, c.isNew).isSuccess) {
                state.markSaved()
            }
        }
    }

    // ─── AI ──────────────────────────────────────────────────────────────

    fun improveNote() {
        if (!ai.isAvailable) return
        scope.launch {
            val c = state.current ?: return@launch
            val result = ai.improve(c.title, c.html)
            if (result is NoteAiResult.Improved) {
                state.applyImprove(result.title, result.body)
            }
            _events.trySend(NotesUiEvent.AiResult(formatNoteAiResult(result)))
        }
    }

    /**
     * Runs the specified [action] and applies the result.
     * Improve and Rewrite apply title+body changes; Summarize, ExtractActions, SuggestTags emit a result event.
     */
    fun runAiAction(action: NoteAiAction) {
        if (!ai.isActionAvailable(action)) return
        scope.launch {
            val c = state.current ?: return@launch
            val result = ai.run(action, c.title, c.html)
            result.fold(
                onSuccess = { success ->
                    when {
                        success is NoteAiResult.Improved -> {
                            state.applyImprove(success.title, success.body)
                            _events.trySend(NotesUiEvent.AiResult(formatNoteAiResult(success)))
                        }
                        success is SummarizeResult -> {
                            _events.trySend(NotesUiEvent.AiResult(formatSummarizeResult(success)))
                        }
                        success is ExtractActionsResult -> {
                            _events.trySend(NotesUiEvent.AiResult(formatExtractActionsResult(success)))
                        }
                        success is SuggestTagsResult -> {
                            _events.trySend(NotesUiEvent.AiResult(formatSuggestTagsResult(success)))
                        }
                    }
                },
                onFailure = { error ->
                    _events.trySend(NotesUiEvent.AiResult("Action failed: ${error.message ?: "unknown"}"))
                },
            )
        }
    }

    // ─── Internal-link picker (inline — trivial mapping) ───────────────────

    suspend fun searchNotesForLink(query: String): List<LinkResult> =
        linkRepo.searchNotes(query).map { LinkResult(it.id.value, it.title, LinkKind.Note) }

    suspend fun searchTasksForLink(query: String): List<LinkResult> =
        linkRepo.searchTasks(query).map { LinkResult(it.id.value, it.title, LinkKind.Task) }

    // ─── Cleanup ─────────────────────────────────────────────────────────

    fun closeEditor() {
        autosaveJob?.cancel()
        state.clear()
    }

    private companion object {
        private val AUTOSAVE_DEBOUNCE = 500L.milliseconds
        private val ZERO_DELAY = 0L.milliseconds
    }
}
