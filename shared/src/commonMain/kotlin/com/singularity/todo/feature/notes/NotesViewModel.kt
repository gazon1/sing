package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

// ─── ViewModel ───────────────────────────────────────────────────────────────

/**
 * Note: This ViewModel uses two coroutine scopes:
 * - [scope] is used for all collection-until-stable flows (init, openEditor).
 *   It uses Dispatchers.Unconfined so flows run synchronously and tests don't need
 *   virtual time advancement.
 * - [viewModelScope] is used for fire-and-forget operations (createNote, delete, autosave)
 *   where immediate return is expected and tests don't need to await them.
 *
 * [scopeProvider] allows tests to inject a custom scope. Defaults to [Dispatchers.Unconfined].
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class NotesViewModel(
    private val store: NotesStore,
    private val htmlPort: MarkdownHtmlPort,
    settingsRepository: SettingsRepository,
    private val scopeProvider: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }
) : ViewModel() {

    /** Scope for collection-until-stable flows (init, openEditor). Lazy to defer Dispatchers.Unconfined access. */
    private val scope: CoroutineScope by lazy { scopeProvider() }

    private val currentUserId = UserId.fromString(runBlocking { settingsRepository.userId.first() })

    // List state
    private val _notes = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val state: StateFlow<NotesUiState> = _notes.asStateFlow()

    // Editor state
    private val _editorState = MutableStateFlow<EditorState>(EditorState.Empty)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    private var autosaveJob: Job? = null

    init {
        scope.launch {
            store.watchAll(currentUserId)
                .map<List<Note>, NotesUiState> { notes ->
                    if (notes.isEmpty()) NotesUiState.Empty(currentUserId)
                    else NotesUiState.Content(notes)
                }
                .catch { emit(NotesUiState.Error(it.message ?: "Error")) }
                .collect { _notes.value = it }
        }
    }

    fun openEditor(noteId: String) {
        scope.launch {
            store.watch(noteId)
                .collect { note ->
                    if (note == null) {
                        _editorState.value = EditorState.Error(noteId, "Note not found")
                    } else {
                        val html = note.bodyMarkdown?.let { htmlPort.toHtml(it) } ?: ""
                        _editorState.value = EditorState.Editing(
                            id = note.id.value,
                            title = note.title,
                            html = html,
                            isDirty = false
                        )
                    }
                }
        }
    }

    fun createNote(): String {
        val id = NoteId.generate()
        viewModelScope.launch {
            store.create(currentUserId, id, "", "")
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

    private fun scheduleAutosave(id: String) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(500) // debounce
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
