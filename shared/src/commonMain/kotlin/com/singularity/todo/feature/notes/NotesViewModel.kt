package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
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
    data class Saving(val id: String) : EditorState
    data class Error(val id: String, val message: String) : EditorState
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
    private val store: NotesStore,
    private val htmlPort: MarkdownHtmlPort,
    settingsRepository: SettingsRepository,
    private val improveNote: ImproveNoteUseCase? = null,
) : ViewModel() {

    private val userId: Flow<UserId> = settingsRepository.userId.map { UserId.fromString(it) }

    private val _notes = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val state: StateFlow<NotesUiState> = _notes.asStateFlow()

    // Editor state
    private val _editorState = MutableStateFlow<EditorState>(EditorState.Empty)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    // AI action results
    private val _aiResult = MutableSharedFlow<NoteAiResult>()
    val aiResult = _aiResult.asSharedFlow()

    // One-shot UI events (dialogs, errors, navigation)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var autosaveJob: Job? = null

    init {
        // viewModelScope ensures cancellation on clear; Unconfined makes synchronous
        // flows (FakeNotesStore) emit without needing virtual time advancement.
        viewModelScope.launch(Dispatchers.Unconfined) {
            userId.flatMapLatest { uid ->
                store.watchAll(uid)
                    .map { notes -> if (notes.isEmpty()) NotesUiState.Empty(uid) else NotesUiState.Content(notes) }
            }
                .catch { emit(NotesUiState.Error(it.message ?: "Error")) }
                .collect { _notes.value = it }
        }
    }

    fun openEditor(noteId: String) {
        // Snapshot once — we don't want every Room update to blast the editor.
        // The rich text state is the source of truth after this point.
        viewModelScope.launch(Dispatchers.Unconfined) {
            val note = store.watch(noteId).filterNotNull().first()
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
        viewModelScope.launch {
            val uid = userId.first()
            store.create(uid, id, "", "")
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

    /**
     * Immediate save — cancels pending autosave and writes synchronously.
     * Used by the Save button in the top bar.
     */
    fun saveNow() {
        val current = _editorState.value as? EditorState.Editing ?: return
        autosaveJob?.cancel()
        viewModelScope.launch {
            _editorState.value = EditorState.Saving(current.id)
            try {
                val markdown = htmlPort.toMarkdown(current.html)
                store.update(current.id, current.title, markdown)
                _editorState.value = current.copy(isDirty = false)
                _events.emit(UiEvent.NavigateBack)
            } catch (e: Exception) {
                _editorState.value = EditorState.Error(current.id, e.message ?: "Save failed")
            }
        }
    }

    private fun scheduleAutosave(id: String) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(500.milliseconds) // debounce
            val current = _editorState.value as? EditorState.Editing ?: return@launch
            _editorState.value = EditorState.Saving(id)
            try {
                val markdown = htmlPort.toMarkdown(current.html)
                store.update(id, current.title, markdown)
                _editorState.value = current.copy(isDirty = false)
            } catch (e: Exception) {
                _editorState.value = EditorState.Error(id, e.message ?: "Save failed")
            }
        }
    }

    fun improveNote() {
        val tool = improveNote ?: return
        viewModelScope.launch {
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
        viewModelScope.launch {
            store.softDelete(id.value)
        }
    }
}
