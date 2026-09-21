package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeleteNoteInput(val noteId: String)

@Serializable
data class DeleteNoteOutput(val noteId: String, val deleted: Boolean, val error: String? = null)

class DeleteNoteTool(private val notesRepository: NotesRepository) :
    SimpleTool<DeleteNoteInput>(TypeToken.of(DeleteNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteNoteInput): String {
        val result = notesRepository.delete(NoteId(args.noteId))
        return Json.encodeToString(
            DeleteNoteOutput.serializer(),
            DeleteNoteOutput(
                noteId = args.noteId,
                deleted = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_note"
        const val DESCRIPTION = "Soft-deletes a note by its ID."
    }
}
