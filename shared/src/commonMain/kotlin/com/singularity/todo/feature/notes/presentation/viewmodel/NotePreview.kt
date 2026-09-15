package com.singularity.todo.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.search.InternalLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
) : ViewModel() {

    private val userId = currentUser.scopedUserId

    private val _state = MutableStateFlow<NotePreviewState>(NotePreviewState.Loading)
    val state: StateFlow<NotePreviewState> = _state.asStateFlow()

    fun loadNote(noteId: String) {
        viewModelScope.launch(Dispatchers.Unconfined) {
            repo.watchNote(NoteId.fromString(noteId))
                .filterNotNull()
                .collect { note ->
                    val backlinks = try {
                        linkRepo.getBacklinkNotes(noteId)
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
        viewModelScope.launch(Dispatchers.Unconfined) {
            repo.softDelete(current.note.id).getOrThrow()
        }
    }
}

sealed interface NotePreviewState {
    data object Loading : NotePreviewState
    data class Loaded(
        val note: Note,
        val backlinks: List<Note>,
    ) : NotePreviewState {
        val backlinkCount: Int get() = backlinks.size
    }
}
