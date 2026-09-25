package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder
import com.singularity.todo.feature.notes.NotesListState
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.presentation.NotesIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Notes list screen ViewModel.
 *
 * Owns: note list filtered by [NoteFilter], sorted by [NoteSortOrder].
 * Triggers: filter/sort changes, note create/delete/restore.
 * One-shot events: [NotesUiEvent.NavigateToEditor], [NotesUiEvent.NavigateToPreview],
 *   [NotesUiEvent.ShowError].
 *
 * @see NotesListState
 * @see NotesUiState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(
    private val repo: NotesRepository,
    private val idGen: IdGenerator,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<NotesUiState, NotesIntent, NotesUiEvent>(
        initialState = NotesUiState.Loading,
        scope = scope,
    ) {

    init {
        addCloseable(scope)
    }

    private val _filter = MutableStateFlow(NoteFilter.All)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(NoteSortOrder.UpdatedDesc)
    val sortOrder: StateFlow<NoteSortOrder> = _sortOrder.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<NoteId>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    init {
        scope.launch {
            // Watch notes based on current filter, then split into pinned/unpinned.
            _filter.flatMapLatest { f ->
                val flow = when (f) {
                    NoteFilter.All -> repo.observeAll()
                    NoteFilter.Pinned -> repo.watchPinned()
                    NoteFilter.Archived -> repo.watchArchived()
                }
                flow.map { notes -> f to notes }
            }
                .catch { emit(NoteFilter.All to emptyList()) }
                .collect { (filter, allNotes) ->
                    if (allNotes.isEmpty() && filter == NoteFilter.All) {
                        __state.value = NotesUiState.Empty
                    } else {
                        val sorted = sortNotes(allNotes, _sortOrder.value)
                        val pinned = sorted.filter { it.isPinned }
                        val unpinned = sorted.filter { !it.isPinned }
                        __state.value = NotesUiState.Content(
                            NotesListState(
                                pinned = pinned,
                                unpinned = unpinned,
                                filter = filter,
                                sortOrder = _sortOrder.value,
                                selectedIds = _selectedIds.value,
                                isSelectionMode = _isSelectionMode.value,
                            ),
                        )
                    }
                }
        }
    }

    private fun sortNotes(notes: List<Note>, order: NoteSortOrder): List<Note> = when (order) {
        NoteSortOrder.UpdatedDesc -> notes.sortedByDescending { it.updatedAt }
        NoteSortOrder.UpdatedAsc -> notes.sortedBy { it.updatedAt }
        NoteSortOrder.TitleAsc -> notes.sortedBy { it.title.lowercase() }
        NoteSortOrder.TitleDesc -> notes.sortedByDescending { it.title.lowercase() }
    }

    override fun onIntent(intent: NotesIntent) {
        when (intent) {
            is NotesIntent.Delete -> delete(intent.id)
            is NotesIntent.TogglePin -> togglePin(intent.id)
            is NotesIntent.SetFilter -> setFilter(intent.filter)
            is NotesIntent.SetSortOrder -> setSortOrder(intent.order)
            is NotesIntent.EnterSelection -> enterSelectionMode(intent.id)
            is NotesIntent.ToggleSelection -> toggleSelection(intent.id)
            NotesIntent.ExitSelection -> exitSelectionMode()
            NotesIntent.DeleteSelected -> deleteSelected()
            is NotesIntent.CreateNote -> createNoteWithTitle(intent.title)
            is NotesIntent.DeleteNote -> delete(intent.id)
        }
    }

    // ─── Filter / Sort ───────────────────────────────────────────────────────

    private fun setFilter(filter: NoteFilter) {
        _filter.value = filter
    }

    private fun setSortOrder(order: NoteSortOrder) {
        _sortOrder.value = order
        // Re-sort current content if already loaded.
        val current = __state.value
        if (current is NotesUiState.Content) {
            val sorted = sortNotes(current.list.pinned + current.list.unpinned, order)
            val pinned = sorted.filter { it.isPinned }
            val unpinned = sorted.filter { !it.isPinned }
            __state.value = current.copy(
                list = current.list.copy(pinned = pinned, unpinned = unpinned, sortOrder = order),
            )
        }
    }

    // ─── Pin ───────────────────────────────────────────────────────────────

    private fun togglePin(id: NoteId) {
        val current = __state.value as? NotesUiState.Content
            ?: return
        val note = (current.list.pinned + current.list.unpinned).firstOrNull { it.id == id }
            ?: return
        scope.fireAndForget(
            errorLabel = "Pin failed",
            onError = { e -> scope.launch { emit(NotesUiEvent.Error("Pin failed: ${e.message ?: "unknown"}")) } },
        ) {
            repo.setPinned(id, !note.isPinned)
        }
    }

    // ─── Archive ───────────────────────────────────────────────────────────

    private fun archive(id: NoteId) {
        scope.fireAndForget(
            errorLabel = "Archive failed",
            onError = { e -> scope.launch { emit(NotesUiEvent.Error("Archive failed: ${e.message ?: "unknown"}")) } },
        ) {
            repo.archive(id)
        }
    }

    // ─── Multi-select ──────────────────────────────────────────────────────

    private fun enterSelectionMode(id: NoteId) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(id)
    }

    private fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    private fun toggleSelection(id: NoteId) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
        if (_selectedIds.value.isEmpty()) {
            _isSelectionMode.value = false
        }
    }

    private fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        scope.launch(Dispatchers.Unconfined) {
            ids.forEach { id ->
                repo.delete(id)
            }
            exitSelectionMode()
        }
    }

    // ─── Quick-create ──────────────────────────────────────────────────────

    /** Creates a note with the given title. */
    fun createNoteWithTitle(title: String): String {
        val id = NoteId(idGen.next())
        scope.launch(Dispatchers.Unconfined) {
            repo.createNoteWithTitle(title)
        }
        return id.value
    }

    // ─── Delete ───────────────────────────────────────────────────────────

    private fun delete(id: NoteId) {
        scope.launch(Dispatchers.Unconfined) {
            repo.delete(id)
        }
    }
}
