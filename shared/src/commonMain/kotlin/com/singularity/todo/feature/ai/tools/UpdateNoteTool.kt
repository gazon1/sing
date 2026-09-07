package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UpdateNoteInput(
    val noteId: String,
    val title: String? = null,
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
)

@Serializable
data class UpdateNoteOutput(
    val noteId: String,
    val updated: Boolean,
)

class UpdateNoteTool(
    private val notesRepository: NotesRepository,
    private val clock: Clock,
) : SimpleTool<UpdateNoteInput>(TypeToken.of(UpdateNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: UpdateNoteInput): String {
        val existing = notesRepository.watchNote(NoteId(args.noteId)).first()
            ?: return Json.encodeToString(
                UpdateNoteOutput.serializer(),
                UpdateNoteOutput(args.noteId, false),
            )

        val updatedNote = existing.copy(
            title = args.title ?: existing.title,
            bodyMarkdown = args.bodyMarkdown ?: existing.bodyMarkdown,
            bodyHtml = args.bodyHtml ?: existing.bodyHtml,
            updatedAt = clock.now(),
        )
        notesRepository.update(updatedNote)
        return Json.encodeToString(
            UpdateNoteOutput.serializer(),
            UpdateNoteOutput(args.noteId, true),
        )
    }

    companion object {
        const val NAME = "update_note"
        const val DESCRIPTION = "Updates an existing note's title and/or body."
    }
}
