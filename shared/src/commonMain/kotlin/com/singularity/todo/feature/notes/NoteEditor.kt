package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─── Editor screen state ──────────────────────────────────────────────────────

sealed interface EditorState {
    data object Empty : EditorState
    data class Editing(
        val id: String,
        val title: String,
        val html: String,
        val isDirty: Boolean = false
    ) : EditorState
}

sealed interface NoteAiResult {
    data class Improved(val title: String, val body: String) : NoteAiResult
    data class Error(val message: String) : NoteAiResult
}

// ─── ViewModel ───────────────────────────────────────────────────────────────

/**
 * Manages the note editing session: title, body, autosave, and AI improve.
 *
 * Each editing session is independent — [openEditor] or [createNote] replaces
 * any prior session state. The caller is responsible for navigating away or
 * showing a "new note" prompt as appropriate.
 *
 * @param improveNote optional AI note improvement; when absent the improve
 *                     button is hidden in UI.
 */
open class NoteEditor(
    private val repo: NotesRepository,
    currentUser: ProfileAwareCurrentUser,
    private val idGen: IdGenerator,
    private val autosaveScheduler: AutosaveScheduler,
    private val improveNote: ImproveNoteUseCase? = null,
    logger: Logger? = null,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val log: Logger = logger ?: Logger.withTag("NoteEditor")
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val userId = currentUser.scopedUserId

    // Editor state — only `Empty` and `Editing`. Saves happen in the background
    // without remounting EditorBody (the previous `Editing ↔ Saving` swap caused
    // recomposition that wiped in-progress text on every keystroke).
    private val _editorState = MutableStateFlow<EditorState>(EditorState.Empty)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    // AI action results
    private val _aiResult = MutableSharedFlow<NoteAiResult>()

    // One-shot "Saved" pulse — triggers the Saved-pill animation in the UI.
    // Uses extraBufferCapacity=1 so rapid saves don't drop the signal.
    private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

    // One-shot UI events (errors, navigation)
    private val _events = MutableSharedFlow<NotesUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<NotesUiEvent> = _events.asSharedFlow()

    private var autosaveJob: Job? = null

    /**
     * Opens an existing note for editing.
     * If the editor already has the same note id loaded, this is a no-op —
     * in-memory edits are preserved even if the underlying DB row hasn't saved yet.
     */
    fun openEditor(noteId: String) {
        scope.launch(Dispatchers.Unconfined) {
            val current = _editorState.value
            if (current is EditorState.Editing && current.id == noteId) return@launch

            val note = repo.watchNote(NoteId.fromString(noteId)).filterNotNull().first()
            // Prefer stored HTML (lossless). Fall back to markdown→HTML for legacy notes.
            val html = note.bodyHtml
                ?: note.bodyMarkdown?.let {
                    // Legacy fallback: convert markdown to HTML using RichTextState
                    com.mohamedrejeb.richeditor.model.RichTextState().apply {
                        setMarkdown(it)
                    }.toHtml()
                }
                ?: ""
            _editorState.value = EditorState.Editing(
                id = note.id.value,
                title = note.title,
                html = html,
                isDirty = false
            )
        }
    }

    /**
     * Creates a new empty note and opens it for editing.
     * Returns the new note id so the caller can navigate.
     */
    fun createNote(): String {
        val id = NoteId.fromString(idGen.next())
        scope.launch(Dispatchers.Unconfined) {
            val uid = userId.value
            repo.createWithContent(uid, id, "", "", "").getOrThrow()
        }
        _editorState.value = EditorState.Editing(
            id = id.value,
            title = "",
            html = "",
            isDirty = false
        )
        return id.value
    }

    fun editTitle(id: String, title: String) {
        val current = _editorState.value as? EditorState.Editing ?: return
        _editorState.value = current.copy(title = title, isDirty = true)
        scheduleAutosave(id)
    }

    fun editBody(id: String, html: String) {
        val current = _editorState.value as? EditorState.Editing ?: return
        _editorState.value = current.copy(html = html, isDirty = true)
        scheduleAutosave(id)
    }

    /** Immediate save — cancels pending autosave. Errors route through events. */
    fun saveNow() {
        val current = _editorState.value as? EditorState.Editing ?: return
        autosaveJob?.cancel()
        scope.launch(Dispatchers.Unconfined) {
            persist(html = current.html, title = current.title, id = current.id, navigateBack = true)
        }
    }

    private fun scheduleAutosave(id: String) {
        autosaveJob?.cancel()
        autosaveJob = scope.launch(Dispatchers.Unconfined) {
            autosaveScheduler.awaitTick()
            val current = _editorState.value as? EditorState.Editing ?: return@launch
            persist(html = current.html, title = current.title, id = current.id, navigateBack = false)
        }
    }

    /**
     * Persists the current editor state to the repository.
     * Extracted to a private method so both [saveNow] and [scheduleAutosave]
     * share the exact same write logic — no duplication, no divergence.
     */
    private suspend fun persist(html: String, title: String, id: String, navigateBack: Boolean) {
        try {
            val markdown = com.mohamedrejeb.richeditor.model.RichTextState().apply {
                setHtml(html)
            }.toMarkdown()
            repo.updateContent(
                NoteId.fromString(id),
                title,
                markdown,
                html
            ).getOrThrow()

            // Extract and persist outgoing wikilinks from the HTML
            val outgoingLinks = extractOutgoingLinks(html).map { link ->
                when (link) {
                    is LinkRef.Note -> "note://${link.noteId}"
                    is LinkRef.Task -> "task://${link.taskId}"
                }
            }
            repo.setOutgoingLinks(NoteId.fromString(id), outgoingLinks).getOrThrow()

            val current = _editorState.value as? EditorState.Editing ?: return
            _editorState.value = current.copy(isDirty = false)
            _savedPulse.emit(Unit)
            if (navigateBack) {
                _events.emit(NotesUiEvent.NavigateBack)
            }
        } catch (e: Exception) {
            log.e(e) { "save failed for note $id" }
            _events.emit(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
        }
    }

    fun improveNote() {
        val tool = improveNote ?: return
        scope.launch(Dispatchers.Unconfined) {
            val current = _editorState.value as? EditorState.Editing ?: return@launch
            tool(current.title, current.html)
                .onSuccess { result ->
                    _editorState.value = current.copy(title = result.title, html = result.body, isDirty = true)
                    val r = NoteAiResult.Improved(result.title, result.body)
                    _aiResult.emit(r)
                    _events.emit(NotesUiEvent.AiResult(formatNoteAiResult(r)))
                }
                .onFailure { error ->
                    val r = NoteAiResult.Error(error.message ?: "Failed")
                    _aiResult.emit(r)
                    _events.emit(NotesUiEvent.AiResult(formatNoteAiResult(r)))
                }
        }
    }

    fun closeEditor() {
        autosaveJob?.cancel()
        _editorState.value = EditorState.Empty
    }
}
