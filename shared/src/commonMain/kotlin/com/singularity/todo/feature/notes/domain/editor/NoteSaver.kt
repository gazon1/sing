package com.singularity.todo.feature.notes.domain.editor

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Persists note content and outgoing links.
 * Selects create vs update based on [isNew] (create-on-first-save pattern).
 * Single error path: logs and emits [NotesUiEvent.SaveFailed].
 * Does NOT mutate [com.singularity.todo.feature.notes.EditorState] — callers do that.
 */
internal class NoteSaver(
    private val repo: NotesRepository,
    private val log: Logger,
    private val events: SendChannel<NotesUiEvent>,
    private val savedPulse: MutableSharedFlow<Unit>,
) {
    /**
     * Persists the note.
     * @return [Result.success] on both insert and update success.
     *         [Result.failure] when either content write or links write fails —
     *         in which case the error has already been logged and [NotesUiEvent.SaveFailed] emitted.
     */
    suspend fun save(id: NoteId, title: String, html: String, isNew: Boolean): Result<Unit> {
        val markdown = NoteContentMapper.toMarkdown(html)
        val links = NoteContentMapper.outgoingLinkUrls(html)

        val contentResult = if (isNew) {
            repo.createWithContent(id, title, markdown, html).map { }
        } else {
            repo.updateContent(id, title, markdown, html)
        }
        if (contentResult.isFailure) {
            return fail(contentResult.exceptionOrNull()!!, id)
        }

        val linksResult = repo.setOutgoingLinks(id, links)
        if (linksResult.isFailure) {
            return fail(linksResult.exceptionOrNull()!!, id)
        }

        savedPulse.emit(Unit)
        return Result.success(Unit)
    }

    private fun fail(e: Throwable, id: NoteId): Result<Unit> {
        log.e(e) { "save failed for note ${id.value}" }
        events.trySend(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
        return Result.failure(e)
    }
}
