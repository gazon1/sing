package com.singularity.todo.feature.notes.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.feature.search.InternalLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Read-only ViewModel for the NotePreview (view) screen.
 *
 * Observes a single note and its backlinks. No editing state lives here —
 * editing is always done through [NoteEditor].
 */
class NotePreview(
    private val repo: NotesRepository,
    private val linkRepo: InternalLinkRepository,
    currentUser: ProfileAwareCurrentUser,
    private val scope: AutoCloseableCoroutineScope,
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Production constructor — Koin uses this. */
    constructor(
        repo: NotesRepository,
        linkRepo: InternalLinkRepository,
        currentUser: ProfileAwareCurrentUser,
    ) : this(
        repo = repo,
        linkRepo = linkRepo,
        currentUser = currentUser,
        scope = AutoCloseableCoroutineScope(),
    )

    private val userId = currentUser.scopedUserId

    private val _state = MutableStateFlow<NotePreviewState>(NotePreviewState.Loading)
    val state: StateFlow<NotePreviewState> = _state.asStateFlow()

    private val _events = Channel<NotesUiEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<NotesUiEvent> = _events.receiveAsFlow()

    private var loadNoteJob: Job? = null

    fun loadNote(noteId: String) {
        loadNoteJob?.cancel()
        loadNoteJob = scope.launch(Dispatchers.Unconfined) {
            repo.watchNoteForCurrentUser(NoteId.fromString(noteId))
                .filterNotNull()
                .collect { note ->
                    val backlinks = try {
                        linkRepo.getBacklinkNotes(noteId, userId.value)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    _state.value = NotePreviewState.Loaded(
                        note = note,
                        backlinks = backlinks,
                    )
                }
        }
    }

    fun delete() {
        val current = _state.value as? NotePreviewState.Loaded ?: return
        scope.fireAndForget(
            errorLabel = "Delete failed",
            onError = { e -> _events.trySend(NotesUiEvent.Error("Delete failed: ${e.message ?: "unknown"}")) },
        ) {
            repo.softDelete(current.note.id)
        }
    }
}

sealed interface NotePreviewState {
    data object Loading : NotePreviewState
    data class Loaded(val note: Note, val backlinks: List<Note>) : NotePreviewState {
        val backlinkCount: Int get() = backlinks.size
    }
}
