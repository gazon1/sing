package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.platform.todayFlow
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteFilter
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteSortOrder
import com.singularity.todo.feature.notes.NotesListState
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.NotesUiState
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.notes.presentation.NotesIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Notes list screen ViewModel.
 *
 * Owns: note list filtered by [NoteFilter] and [searchQueryFlow], sorted by [NoteSortOrder].
 * Triggers: filter/sort/search changes, note create/delete/archive/restore.
 * One-shot events: [NotesUiEvent.NavigateToEditor], [NotesUiEvent.NavigateToPreview],
 *   [NotesUiEvent.ShowError].
 *
 * @see NotesListState
 * @see NotesUiState
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotesListViewModel(
    private val repo: NotesRepository,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<NotesUiState, NotesIntent, NotesUiEvent>(
        initialState = NotesUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    private companion object {
        /** Keystrokes settle for this long before a repository query runs. */
        const val SEARCH_DEBOUNCE_MS = 200L
    }

    // [filter] and [sortOrder] are inputs to the collector below, not public surface:
    // [NotesListState] already carries both, so the screen reads them from the state
    // instead of collecting a second, independently-timed copy of the same value.
    private val filterFlow = MutableStateFlow(NoteFilter.All)

    private val sortOrderFlow = MutableStateFlow(NoteSortOrder.UpdatedDesc)

    /**
     * Raw keystrokes. Debounced before it reaches [filterFlow] so a fast typist
     * triggers one query, not one per character — see [SearchQueryChanged].
     */
    private val searchQueryFlow = MutableStateFlow("")

    /**
     * The query actually applied to the repository.
     *
     * A blank query passes through with no delay so the first list emission is not
     * held hostage by the debounce — otherwise the screen would sit on `Loading`
     * for 200 ms on every launch. Only real keystrokes wait.
     */
    private val debouncedQueryFlow = searchQueryFlow
        .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    private val _selectedIds = MutableStateFlow<Set<NoteId>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    init {
        scope.launch {
            // Combine five flows: notes + templates + daily notes + sort order + search.
            val templatesFlow = repo.watchTemplates()
            val dailyNotesFlow = todayFlow().flatMapLatest { today ->
                val from = today.minus(7, DateTimeUnit.DAY).toString()
                val to = today.plus(30, DateTimeUnit.DAY).toString()
                repo.watchDailyNotesInRange(from, to)
            }
            val notesFlow = combine(filterFlow, debouncedQueryFlow) { f, query -> f to query }
                .flatMapLatest { (f, query) ->
                    val flow = when {
                        query.isNotBlank() -> repo.search(query)
                        f == NoteFilter.All -> repo.observeAll()
                        f == NoteFilter.Pinned -> repo.watchPinned()
                        else -> repo.watchArchived()
                    }
                    flow.map { notes -> Triple(f, query, notes) }
                }
            combine(notesFlow, templatesFlow, dailyNotesFlow, sortOrderFlow) {
                (filter, query, allNotes),
                templates,
                dailyNotes,
                sortOrder,
                ->
                val sorted = sortNotes(allNotes, sortOrder)
                val pinned = sorted.filter { it.isPinned }
                val unpinned = sorted.filter { !it.isPinned }
                NotesUiState.Content(
                    NotesListState(
                        pinned = pinned,
                        unpinned = unpinned,
                        templates = templates,
                        dailyNotes = dailyNotes,
                        filter = filter,
                        sortOrder = sortOrder,
                        searchQuery = query,
                        selectedIds = _selectedIds.value,
                        isSelectionMode = _isSelectionMode.value,
                    ),
                )
            }
                .catch { emit(NotesUiState.Content(NotesListState())) }
                .collect { state -> setState(state) }
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
            is NotesIntent.Archive -> archive(intent.id)
            is NotesIntent.Unarchive -> unarchive(intent.id)
            is NotesIntent.SetFilter -> setFilter(intent.filter)
            is NotesIntent.SetSortOrder -> setSortOrder(intent.order)
            is NotesIntent.SearchQueryChanged -> searchQueryFlow.value = intent.query
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
        filterFlow.value = filter
    }

    private fun setSortOrder(order: NoteSortOrder) {
        sortOrderFlow.value = order
        // Re-sort current content if already loaded.
        val current = currentState
        if (current is NotesUiState.Content) {
            val sorted = sortNotes(current.list.pinned + current.list.unpinned, order)
            val pinned = sorted.filter { it.isPinned }
            val unpinned = sorted.filter { !it.isPinned }
            setState(
                current.copy(
                    list = current.list.copy(pinned = pinned, unpinned = unpinned, sortOrder = order),
                ),
            )
        }
    }

    // ─── Pin ───────────────────────────────────────────────────────────────

    private fun togglePin(id: NoteId) {
        val current = currentState as? NotesUiState.Content
            ?: return
        val note = (current.list.pinned + current.list.unpinned).firstOrNull { it.id == id }
            ?: return
        emitError("Pin failed", { msg -> NotesUiEvent.Error("Pin failed: $msg") }) {
            repo.setPinned(id, !note.isPinned)
        }
    }

    // ─── Archive ───────────────────────────────────────────────────────────

    private fun archive(id: NoteId) {
        emitError("Archive failed", { msg -> NotesUiEvent.Error("Archive failed: $msg") }) {
            repo.archive(id)
        }
    }

    private fun unarchive(id: NoteId) {
        emitError("Unarchive failed", { msg -> NotesUiEvent.Error("Unarchive failed: $msg") }) {
            repo.unarchive(id)
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
        scope.launch {
            ids.forEach { id ->
                repo.delete(id)
            }
            exitSelectionMode()
        }
    }

    // ─── Quick-create ──────────────────────────────────────────────────────

    /**
     * Creates a note with the given title, then asks the screen to open it.
     *
     * The navigation carries [NotesUiEvent.NavigateToEditor] with the id the repository
     * returned. This method deliberately does not return an id: the write is launched, so
     * there is nothing to return synchronously, and a locally generated id would name a
     * note that was never created.
     */
    fun createNoteWithTitle(title: String) {
        scope.launch {
            repo.createNoteWithTitle(title)
                .onSuccess { emit(NotesUiEvent.NavigateToEditor(it)) }
                .onFailure { emit(NotesUiEvent.Error(it.toMessage("Create note failed"))) }
        }
    }

    // ─── Delete ───────────────────────────────────────────────────────────

    private fun delete(id: NoteId) {
        scope.launch {
            repo.delete(id)
        }
    }
}
