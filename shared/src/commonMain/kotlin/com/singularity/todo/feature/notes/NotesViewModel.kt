package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ─── List screen state ────────────────────────────────────────────────────────

/** Filter for the notes list. */
enum class NoteFilter {
    All, Pinned, Archived
}

/** Sort order for the notes list. */
enum class NoteSortOrder {
    UpdatedDesc, UpdatedAsc, TitleAsc, TitleDesc
}

/**
 * UI state for the notes list screen.
 *
 * @param pinned      Pinned notes (always visible at top regardless of filter).
 * @param unpinned    Non-pinned notes matching the current [filter].
 * @param filter     Active filter (All / Pinned / Archived).
 * @param sortOrder  Active sort order.
 * @param selectedIds Notes selected in multi-select mode.
 */
data class NotesListState(
    val pinned: List<Note> = emptyList(),
    val unpinned: List<Note> = emptyList(),
    val filter: NoteFilter = NoteFilter.All,
    val sortOrder: NoteSortOrder = NoteSortOrder.UpdatedDesc,
    val selectedIds: Set<NoteId> = emptySet(),
    val isSelectionMode: Boolean = false,
)

sealed interface NotesUiState {
    data object Loading : NotesUiState
    data class Empty(val userId: UserId) : NotesUiState
    data class Content(val list: NotesListState) : NotesUiState
    data class Error(val message: String) : NotesUiState
}

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
 * Uses [viewModelScope] for all coroutine work.
 * Long-lived subscriptions (init, openEditor) are cancelled automatically in [onCleared].
 *
 * @param improveNote optional AI note improvement; when absent the improve button is hidden in UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class NotesViewModel(
    private val repo: NotesRepository,
    private val htmlPort: MarkdownHtmlPort,
    currentUser: CurrentUser,
    private val idGen: IdGenerator,
    private val autosaveScheduler: AutosaveScheduler,
    private val improveNote: ImproveNoteUseCase? = null,
    logger: Logger? = null,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    // Logger instantiated directly — consistent with SettingsViewModel, AuthRepository, etc.
    // (this codebase uses Logger.withTag() directly, not Koin-injected Logger beans)
    private val log: Logger = logger ?: Logger.withTag("Notes")
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val userId = currentUser.userId

    private val _notes = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val state: StateFlow<NotesUiState> = _notes.asStateFlow()

    private val _filter = MutableStateFlow(NoteFilter.All)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(NoteSortOrder.UpdatedDesc)
    val sortOrder: StateFlow<NoteSortOrder> = _sortOrder.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<NoteId>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    // Editor state — only `Empty` and `Editing`. Saves happen in the background
    // without remounting EditorBody (the previous `Editing ↔ Saving` swap caused
    // recomposition that wiped in-progress text on every keystroke).
    private val _editorState = MutableStateFlow<EditorState>(EditorState.Empty)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    // AI action results
    private val _aiResult = MutableSharedFlow<NoteAiResult>()
    val aiResult = _aiResult.asSharedFlow()

    // One-shot "Saved" pulse — triggers the Saved-pill animation in the UI.
    // Uses extraBufferCapacity=1 so rapid saves don't drop the signal.
    private val _savedPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedPulse: SharedFlow<Unit> = _savedPulse.asSharedFlow()

    // One-shot UI events (dialogs, errors, navigation) — errors from
    // background saves now route through here, not through EditorState.Error.
    private val _events = MutableSharedFlow<NotesUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<NotesUiEvent> = _events.asSharedFlow()

    private var autosaveJob: Job? = null

    init {
        scope.launch(Dispatchers.Unconfined) {
            // Watch notes based on current filter, then split into pinned/unpinned.
            _filter.flatMapLatest { f ->
                val flow = when (f) {
                    NoteFilter.All -> repo.watchNotes(userId.value)
                    NoteFilter.Pinned -> repo.watchPinned(userId.value)
                    NoteFilter.Archived -> repo.watchArchived(userId.value)
                }
                flow.map { notes -> f to notes }
            }.catch { emit(NoteFilter.All to emptyList()) }
                .collect { (filter, allNotes) ->
                    val uid = userId.value
                    if (allNotes.isEmpty() && filter == NoteFilter.All) {
                        _notes.value = NotesUiState.Empty(uid)
                    } else {
                        val sorted = sortNotes(allNotes, _sortOrder.value)
                        val pinned = sorted.filter { it.isPinned }
                        val unpinned = sorted.filter { !it.isPinned }
                        _notes.value = NotesUiState.Content(
                            NotesListState(
                                pinned = pinned,
                                unpinned = unpinned,
                                filter = filter,
                                sortOrder = _sortOrder.value,
                                selectedIds = _selectedIds.value,
                                isSelectionMode = _isSelectionMode.value,
                            )
                        )
                    }
                }
        }
    }

    private fun sortNotes(notes: List<Note>, order: NoteSortOrder): List<Note> {
        return when (order) {
            NoteSortOrder.UpdatedDesc -> notes.sortedByDescending { it.updatedAt }
            NoteSortOrder.UpdatedAsc  -> notes.sortedBy { it.updatedAt }
            NoteSortOrder.TitleAsc   -> notes.sortedBy { it.title.lowercase() }
            NoteSortOrder.TitleDesc  -> notes.sortedByDescending { it.title.lowercase() }
        }
    }

    fun openEditor(noteId: String) {
        scope.launch(Dispatchers.Unconfined) {
            // Guard: if the editor is already open for this id with text the
            // user has been typing, don't clobber it with whatever the repo
            // currently holds (which may still be the empty pre-save state if
            // the autosave hasn't propagated yet). This protects the UI from
            // re-emit races where LaunchedEffect re-triggers openEditor while
            // the editor is already showing the user's content.
            val current = _editorState.value
            if (current is EditorState.Editing && current.id == noteId) return@launch

            val note = repo.watchNote(NoteId.fromString(noteId)).filterNotNull().first()
            // Prefer stored HTML (lossless). Fall back to markdown→HTML for legacy notes.
            val html = note.bodyHtml
                ?: note.bodyMarkdown?.let { htmlPort.toHtml(it) }
                ?: ""
            _editorState.value = EditorState.Editing(
                id = note.id.value,
                title = note.title,
                html = html,
                isDirty = false
            )
        }
    }

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
            try {
                val html = current.html
                val markdown = htmlPort.toMarkdown(html)
                repo.updateContent(NoteId.fromString(current.id), current.title, markdown, html).getOrThrow()
                _editorState.value = current.copy(isDirty = false)
                _savedPulse.emit(Unit)
                _events.emit(NotesUiEvent.NavigateBack)
            } catch (e: Exception) {
                log.e(e) { "saveNow failed for note ${current.id}" }
                _events.emit(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
            }
        }
    }

    private fun scheduleAutosave(id: String) {
        autosaveJob?.cancel()
        autosaveJob = scope.launch(Dispatchers.Unconfined) {
            autosaveScheduler.awaitTick()
            val current = _editorState.value as? EditorState.Editing ?: return@launch
            try {
                val html = current.html
                val markdown = htmlPort.toMarkdown(html)
                repo.updateContent(NoteId.fromString(id), current.title, markdown, html).getOrThrow()
                _editorState.value = current.copy(isDirty = false)
                _savedPulse.emit(Unit)
            } catch (e: Exception) {
                log.e(e) { "autosave failed for note $id" }
                // Autosave failures are silent — do not emit SaveFailed to UI
                // to avoid spamming the user with snackbars during typing.
            }
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

    fun delete(id: NoteId) {
        scope.launch(Dispatchers.Unconfined) {
            repo.softDelete(id)
        }
    }

    // ─── Filter / Sort ───────────────────────────────────────────────────────

    fun setFilter(filter: NoteFilter) {
        _filter.value = filter
    }

    fun setSortOrder(order: NoteSortOrder) {
        _sortOrder.value = order
        // Re-sort current content if already loaded.
        val current = _notes.value
        if (current is NotesUiState.Content) {
            val sorted = sortNotes(current.list.pinned + current.list.unpinned, order)
            val pinned = sorted.filter { it.isPinned }
            val unpinned = sorted.filter { !it.isPinned }
            _notes.value = current.copy(
                list = current.list.copy(pinned = pinned, unpinned = unpinned, sortOrder = order)
            )
        }
    }

    // ─── Pin ────────────────────────────────────────────────────────────────

    fun togglePin(id: NoteId) {
        scope.launch(Dispatchers.Unconfined) {
            val current = _notes.value as? NotesUiState.Content ?: return@launch
            val note = (current.list.pinned + current.list.unpinned).firstOrNull { it.id == id } ?: return@launch
            repo.setPinned(id, !note.isPinned).getOrThrow()
        }
    }

    // ─── Archive ───────────────────────────────────────────────────────────

    fun archive(id: NoteId) {
        scope.launch(Dispatchers.Unconfined) {
            repo.archive(id).getOrThrow()
        }
    }

    fun unarchive(id: NoteId) {
        scope.launch(Dispatchers.Unconfined) {
            repo.unarchive(id).getOrThrow()
        }
    }

    // ─── Color ─────────────────────────────────────────────────────────────

    fun setColor(id: NoteId, color: NoteColor?) {
        scope.launch(Dispatchers.Unconfined) {
            repo.setColor(id, color).getOrThrow()
        }
    }

    // ─── Multi-select ──────────────────────────────────────────────────────

    fun enterSelectionMode(id: NoteId) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(id)
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(id: NoteId) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
        if (_selectedIds.value.isEmpty()) {
            _isSelectionMode.value = false
        }
    }

    fun deleteSelected() {
        scope.launch(Dispatchers.Unconfined) {
            _selectedIds.value.forEach { id -> repo.softDelete(id) }
            exitSelectionMode()
        }
    }
}
