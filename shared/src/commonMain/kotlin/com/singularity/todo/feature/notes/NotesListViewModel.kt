package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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

// ─── ViewModel ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(
    private val repo: NotesRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val idGen: IdGenerator,
) : ViewModel() {

    private val userId = currentUser.scopedUserId

    private val _notes = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val state: StateFlow<NotesUiState> = _notes.asStateFlow()

    private val _filter = MutableStateFlow(NoteFilter.All)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(NoteSortOrder.UpdatedDesc)
    val sortOrder: StateFlow<NoteSortOrder> = _sortOrder.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<NoteId>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.Unconfined) {
            // Watch notes based on current filter, then split into pinned/unpinned.
            combine(_filter, userId) { f, uid -> f to uid }
                .flatMapLatest { (f, uid) ->
                    val flow = when (f) {
                        NoteFilter.All -> repo.watchNotes(uid)
                        NoteFilter.Pinned -> repo.watchPinned(uid)
                        NoteFilter.Archived -> repo.watchArchived(uid)
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
        viewModelScope.launch(Dispatchers.Unconfined) {
            val current = _notes.value as? NotesUiState.Content ?: return@launch
            val note = (current.list.pinned + current.list.unpinned).firstOrNull { it.id == id }
                ?: return@launch
            repo.setPinned(id, !note.isPinned).getOrThrow()
        }
    }

    // ─── Archive ───────────────────────────────────────────────────────────

    fun archive(id: NoteId) {
        viewModelScope.launch(Dispatchers.Unconfined) {
            repo.archive(id).getOrThrow()
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
        viewModelScope.launch(Dispatchers.Unconfined) {
            _selectedIds.value.forEach { id -> repo.softDelete(id) }
            exitSelectionMode()
        }
    }

    // ─── Quick-create ──────────────────────────────────────────────────────

    /** Creates a note with the given title and returns its id. */
    fun createNoteWithTitle(title: String): String {
        val id = NoteId(idGen.next())
        viewModelScope.launch(Dispatchers.Unconfined) {
            repo.createNoteWithTitle(userId.value, title)
        }
        return id.value
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    fun delete(id: NoteId) {
        viewModelScope.launch(Dispatchers.Unconfined) {
            repo.softDelete(id)
        }
    }
}
