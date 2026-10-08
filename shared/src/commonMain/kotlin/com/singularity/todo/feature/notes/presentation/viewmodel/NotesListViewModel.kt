package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
@Suppress("TooManyFunctions") // 16 functions: undo path adds 4 internal helpers
class NotesListViewModel(
    private val repo: NotesRepository,
    // `todayFlow` requires both now (#91). Defaulted rather than required: daily
    // notes are grouped by the host's day and no test in this tree asserts that
    // grouping, so a required parameter would be a signature change with no failing
    // test behind it. The parameter exists so one can be added.
    private val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    private val timeZone: com.singularity.todo.core.platform.TimeZoneProvider =
        com.singularity.todo.core.platform.systemTimeZone,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<NotesUiState, NotesIntent, NotesUiEvent>(
        initialState = NotesUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * Local reference for use in suspend contexts where the parent class's
     * private `crashReporter` is not accessible.
     */
    private val crashReporter: CrashReportingPort = crashReporter

    private companion object {
        /** Keystrokes settle for this long before a repository query runs. */
        const val SEARCH_DEBOUNCE_MS = 200L

        /** 5-second undo window, matching the snackbar duration. */
        const val UNDO_WINDOW_MS = 5_000L

        private const val DELETE_FAILED = "notes.delete_failed"
        private const val RESTORE_FAILED = "notes.restore_failed"
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

    /**
     * Tracks a soft-deleted note pending undo, so the snackbar can offer a 5-second window.
     * Null when no delete is pending.
     */
    private val _pendingDelete = MutableStateFlow<PendingDelete?>(null)
    val pendingDelete = _pendingDelete.asStateFlow()

    /** Cooldown job for clearing [_pendingDelete] after the undo window expires. */
    private var pendingDeleteJob: Job? = null

    init {
        scope.launch {
            // Combine five flows: notes + templates + daily notes + sort order + search.
            val templatesFlow = repo.watchTemplates()
            val dailyNotesFlow = todayFlow(clock, timeZone.current()).flatMapLatest { today ->
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
            is NotesIntent.Delete -> handleDelete(intent.id)
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
            is NotesIntent.DeleteNote -> handleDelete(intent.id)
            is NotesIntent.UndoDelete -> onUndoDeleteIntent()
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

    /**
     * Deletes every selected note.
     *
     * Every delete is attempted even if one fails — a bulk operation that stops at the
     * first failure leaves the selection describing a half-applied request — and the
     * first failure is what gets reported. Which one is arbitrary, and saying so is
     * honest: the alternative is to report nothing until every delete had run, and a
     * user watching a selection clear has no way to tell that several notes did not go.
     */
    private fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        emitError("Delete failed", { msg -> NotesUiEvent.Error("Delete failed: $msg") }) {
            val firstFailure = ids.map { id -> repo.delete(id) }.firstOrNull { it.isFailure }
            exitSelectionMode()
            firstFailure ?: Result.success(Unit)
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

    // ─── Delete / Undo ──────────────────────────────────────────────────

    /**
     * Soft-deletes a note and emits [NotesUiEvent.UndoDelete] so the UI can show a snackbar.
     * The snackbar offers a 5-second undo window; if not tapped, [pendingDeleteJob]
     * calls [repo.delete]. If the user taps Undo, [onUndoDelete] calls [repo.restore].
     *
     * The note disappears from the list optimistically — [repo.delete] is called only
     * after the undo window expires. This matches the behaviour of [AgendaViewModel]
     * for task deletion.
     */
    private fun handleDelete(noteId: NoteId) {
        val noteTitle = findNoteTitle(noteId)

        // Cancel any existing undo window — a new delete supersedes it.
        pendingDeleteJob?.cancel()

        // Store the pending delete and emit the event.
        _pendingDelete.value = PendingDelete(noteId, noteTitle)
        scope.launch { emit(NotesUiEvent.UndoDelete(noteId, noteTitle)) }

        // Kick off the 5-second undo window. When it expires, commit the delete.
        pendingDeleteJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            _pendingDelete.value = null
            repo.delete(noteId)
                .onFailure { crashReporter.report(it, DELETE_FAILED) }
        }
    }

    /**
     * Restores the last soft-deleted note, cancelling the undo window.
     * Called when the user taps "Undo" on the snackbar.
     */
    private suspend fun onUndoDelete(noteId: NoteId) {
        pendingDeleteJob?.cancel()
        _pendingDelete.value = null
        repo.restore(noteId)
            .onFailure { crashReporter.report(it, RESTORE_FAILED) }
    }

    /**
     * Call this from the UI when the user taps "Undo" on the snackbar.
     * The UI layer holds the snackbar reference and invokes this method directly.
     */
    fun onUndoDeleteIntent() {
        val pending = _pendingDelete.value ?: return
        scope.launch { onUndoDelete(pending.noteId) }
    }

    private fun findNoteTitle(noteId: NoteId): String {
        val current = currentState as? NotesUiState.Content ?: return "Note"
        val note = current.list.pinned
            .plus(current.list.unpinned)
            .plus(current.list.templates)
            .plus(current.list.dailyNotes)
            .firstOrNull { it.id == noteId }
        return note?.title?.ifEmpty { "Note" } ?: "Note"
    }
}

/**
 * A note that has been soft-deleted and is pending an undo window.
 *
 * @param noteId The deleted note id.
 * @param title  Short label for the snackbar.
 */
data class PendingDelete(val noteId: NoteId, val title: String)
