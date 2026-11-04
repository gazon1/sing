package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<NotePreviewState, NotePreviewIntent, NotesUiEvent>(
        initialState = NotePreviewState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {
    private val logger = Logger.withTag("NotePreview")
    private var loadNoteJob: Job? = null

    override fun onIntent(intent: NotePreviewIntent) {
        when (intent) {
            is NotePreviewIntent.Load -> loadNote(intent.noteId)
            NotePreviewIntent.Refresh -> refresh()
            NotePreviewIntent.Delete -> delete()
        }
    }

    private fun refresh() {
        val currentId = (currentState as? NotePreviewState.Loaded)?.note?.id ?: return
        loadNote(currentId.value)
    }

    private fun loadNote(noteId: String) {
        loadNoteJob?.cancel()
        // A pane keeps one ViewModel across every note it shows, so a switch from one
        // note to another would otherwise leave the previous note on screen until the
        // new one resolved — the user clicks B and reads A. Only a *different* note
        // resets: a refresh of the note already shown must not blank it.
        if ((currentState as? NotePreviewState.Loaded)?.note?.id?.value != noteId) {
            updateState { NotePreviewState.Loading }
        }
        loadNoteJob =
            vmScope.launch {
                val note = repo.get(NoteId.fromString(noteId))
                if (note == null) {
                    updateState { NotePreviewState.NotFound }
                    return@launch
                }
                val backlinks =
                    try {
                        linkRepo.getBacklinkNotes(noteId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(e) { "Failed to load backlink notes" }
                        emptyList()
                    }
                updateState { NotePreviewState.Loaded(note = note, backlinks = backlinks.toImmutableList()) }
            }
    }

    private fun delete() {
        val current = currentState as? NotePreviewState.Loaded ?: return
        emitError("Delete failed", { msg -> NotesUiEvent.Error("Delete failed: $msg") }) {
            // The repository reports a failed delete by *returning* a failed `Result`, not
            // by throwing, and `emitError` routes that same `Result`'s failure to the error
            // event. So `NotFound` is set from the success branch only: a delete that did
            // not land leaves the note `Loaded` and the surface where it is. Setting it
            // unconditionally (or after an ignored `delete(...)`) would drive a host that
            // leaves on `NotFound` to close the pane/pop the route while the note is still
            // in the list — the exact regression the state change exists to prevent.
            repo.delete(current.note.id).onSuccess {
                updateState { NotePreviewState.NotFound }
            }
        }
    }
}

sealed interface NotePreviewState {
    data object Loading : NotePreviewState

    data object NotFound : NotePreviewState

    data class Loaded(val note: Note, val backlinks: ImmutableList<Note>) : NotePreviewState {
        val backlinkCount: Int get() = backlinks.size
    }
}

/** One-shot intents for [NotePreview]. */
sealed interface NotePreviewIntent : MviIntent {
    data class Load(val noteId: String) : NotePreviewIntent

    data object Refresh : NotePreviewIntent

    data object Delete : NotePreviewIntent
}
