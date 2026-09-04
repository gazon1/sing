package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.milliseconds

// ─── List screen state ────────────────────────────────────────────────────────

sealed interface NotesUiState {
    data object Loading : NotesUiState
    data class Empty(val userId: UserId) : NotesUiState
    data class Content(val notes: List<Note>) : NotesUiState
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
    private val improveNote: ImproveNoteUseCase? = null,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val userId = currentUser.userId

    private val _notes = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val state: StateFlow<NotesUiState> = _notes.asStateFlow()

    // Editor state — only `Empty` and `Editing`. Saves happen in the background
    // without remounting EditorBody (the previous `Editing ↔ Saving` swap caused
    // recomposition that wiped in-progress text on every keystroke).
    private val _editorState = MutableStateFlow<EditorState>(EditorState.Empty)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    // AI action results
    private val _aiResult = MutableSharedFlow<NoteAiResult>()
    val aiResult = _aiResult.asSharedFlow()

    // One-shot UI events (dialogs, errors, navigation) — errors from
    // background saves now route through here, not through EditorState.Error.
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var autosaveJob: Job? = null

    init {
        scope.launch(Dispatchers.Unconfined) {
            userId.flatMapLatest { uid ->
                repo.watchNotes(uid)
                    .map { notes -> if (notes.isEmpty()) NotesUiState.Empty(uid) else NotesUiState.Content(notes) }
            }
                .catch { emit(NotesUiState.Error(it.message ?: "Error")) }
                .collect { _notes.value = it }
        }
    }

    fun openEditor(noteId: String) {
        scope.launch(Dispatchers.Unconfined) {
            val note = repo.watchNote(NoteId.fromString(noteId)).filterNotNull().first()
            val html = note.bodyMarkdown?.let { htmlPort.toHtml(it) } ?: ""
            _editorState.value = EditorState.Editing(
                id = note.id.value,
                title = note.title,
                html = html,
                isDirty = false
            )
        }
    }

    fun createNote(): String {
        val id = NoteId.generate()
        scope.launch {
            val uid = userId.value
            repo.createWithContent(uid, id, "", "").getOrThrow()
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
                val markdown = htmlPort.toMarkdown(current.html)
                repo.updateContent(NoteId.fromString(current.id), current.title, markdown).getOrThrow()
                _editorState.value = current.copy(isDirty = false)
                _events.emit(UiEvent.NavigateBack)
            } catch (e: Exception) {
                _events.emit(UiEvent.ShowError(e.message ?: "Save failed"))
            }
        }
    }

    private fun scheduleAutosave(id: String) {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(500.milliseconds)
            val current = _editorState.value as? EditorState.Editing ?: return@launch
            try {
                val markdown = htmlPort.toMarkdown(current.html)
                repo.updateContent(NoteId.fromString(id), current.title, markdown).getOrThrow()
                _editorState.value = current.copy(isDirty = false)
            } catch (e: Exception) {
                _events.emit(UiEvent.ShowError(e.message ?: "Save failed"))
            }
        }
    }

    fun improveNote() {
        val tool = improveNote ?: return
        scope.launch {
            val current = _editorState.value as? EditorState.Editing ?: return@launch
            tool(current.title, current.html)
                .onSuccess { result ->
                    _editorState.value = current.copy(title = result.title, html = result.body, isDirty = true)
                    val r = NoteAiResult.Improved(result.title, result.body)
                    _aiResult.emit(r)
                    _events.emit(UiEvent.ShowDialog(title = "AI Result", text = formatNoteAiResult(r)))
                }
                .onFailure { error ->
                    val r = NoteAiResult.Error(error.message ?: "Failed")
                    _aiResult.emit(r)
                    _events.emit(UiEvent.ShowDialog(title = "AI Result", text = formatNoteAiResult(r)))
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
}
