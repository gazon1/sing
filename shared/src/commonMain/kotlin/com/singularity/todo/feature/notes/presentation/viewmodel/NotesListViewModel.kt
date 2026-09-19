package com.singularity.todo.feature.notes.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder
import com.singularity.todo.feature.notes.NotesListState
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.core.coroutines.fireAndForget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Notes list screen ViewModel.
 *
 * Owns: note list filtered by [NoteFilter], sorted by [NoteSortOrder].
 * Triggers: filter/sort changes, note create/delete/restore.
 * One-shot events: [NotesUiEvent.NavigateToEditor], [NotesUiEvent.NavigateToPreview],
 *   [NotesUiEvent.ShowError].
 *
 * @see NotesListState
 * @see NotesUiEvent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(
    private val repo: NotesRepository,
    currentUser: ProfileAwareCurrentUser,
    private val idGen: IdGenerator,
    private val scope: CoroutineScope,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {

    // Secondary — production Koin uses this
    constructor(
        repo: NotesRepository,
        currentUser: ProfileAwareCurrentUser,
        idGen: IdGenerator,
    ) : this(
        repo, currentUser, idGen,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val userId = currentUser.scopedUserId

    private val _notes = MutableStateFlow<NotesUiState>(NotesUiState.Loading)
    val state: StateFlow<NotesUiState> = _notes.asStateFlow()

    private val _filter = MutableStateFlow(NoteFilter.All)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _sortOrder = MutableStateFlow(NoteSortOrder.UpdatedDesc)
    val sortOrder: StateFlow<NoteSortOrder> = _sortOrder.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<NoteId>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    private val _events = Channel<NotesUiEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<NotesUiEvent> = _events.receiveAsFlow()

    init {
        scope.launch(Dispatchers.Unconfined) {
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
                list = current.list.copy(pinned = pinned, unpinned = unpinned, sortOrder = order),
            )
        }
    }

    // ─── Pin ────────────────────────────────────────────────────────────────

    fun togglePin(id: NoteId) {
        val current = _notes.value as? NotesUiState.Content ?: return
        val note = (current.list.pinned + current.list.unpinned).firstOrNull { it.id == id }
            ?: return
        scope.fireAndForget(
            errorLabel = "Pin failed",
            onError = { e -> _events.trySend(NotesUiEvent.Error("Pin failed: ${e.message ?: "unknown"}")) },
        ) {
            repo.setPinned(id, !note.isPinned)
        }
    }

    // ─── Archive ───────────────────────────────────────────────────────────

    fun archive(id: NoteId) {
        scope.fireAndForget(
            errorLabel = "Archive failed",
            onError = { e -> _events.trySend(NotesUiEvent.Error("Archive failed: ${e.message ?: "unknown"}")) },
        ) {
            repo.archive(id)
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
        val ids = _selectedIds.value.toList()
        scope.launch(Dispatchers.Unconfined) {
            ids.forEach { id ->
                repo.softDelete(id)
            }
            exitSelectionMode()
        }
    }

    // ─── Quick-create ──────────────────────────────────────────────────────

    /** Creates a note with the given title and returns its id. */
    fun createNoteWithTitle(title: String): String {
        val id = NoteId(idGen.next())
        scope.launch(Dispatchers.Unconfined) {
            repo.createNoteWithTitle(userId.value, title)
        }
        return id.value
    }

    // ─── Delete ────────────────────────────────────────────────────────────

    fun delete(id: NoteId) {
        scope.launch(Dispatchers.Unconfined) {
            repo.softDelete(id)
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
