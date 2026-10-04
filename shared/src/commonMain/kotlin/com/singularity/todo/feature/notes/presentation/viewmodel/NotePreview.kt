package com.singularity.todo.feature.notes.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
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
        loadNoteJob = vmScope.launch {
            val note = repo.observe(NoteId.fromString(noteId))
                .filterNotNull()
                .first()
            val backlinks = try {
                linkRepo.getBacklinkNotes(noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "Failed to load backlink notes" }
                emptyList()
            }
            updateState { NotePreviewState.Loaded(note = note, backlinks = backlinks) }
        }
    }

    private fun delete() {
        val current = currentState as? NotePreviewState.Loaded ?: return
        emitError("Delete failed", { msg -> NotesUiEvent.Error("Delete failed: $msg") }) {
            repo.delete(current.note.id)
        }
    }
}

sealed interface NotePreviewState {
    data object Loading : NotePreviewState
    data class Loaded(val note: Note, val backlinks: List<Note>) : NotePreviewState {
        val backlinkCount: Int get() = backlinks.size
    }
}

/** One-shot intents for [NotePreview]. */
sealed interface NotePreviewIntent : MviIntent {
    data class Load(val noteId: String) : NotePreviewIntent
    data object Refresh : NotePreviewIntent
    data object Delete : NotePreviewIntent
}
