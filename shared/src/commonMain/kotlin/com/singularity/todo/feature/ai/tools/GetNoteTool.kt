package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import kotlinx.serialization.Serializable

@Serializable
data class GetNoteInput(val noteId: String)

@Serializable
data class GetNoteOutput(val id: String, val title: String, val bodyMarkdown: String?)

class GetNoteTool(private val notesRepo: NotesRepository) :
    SimpleTool<GetNoteInput>(TypeToken.of(GetNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GetNoteInput): String {
        val note = notesRepo.get(NoteId.fromString(args.noteId))
        val output = if (note != null) {
            GetNoteOutput(note.id.value, note.title, note.bodyMarkdown)
        } else {
            GetNoteOutput(args.noteId, "(not found)", null)
        }
        return kotlinx.serialization.json.Json.encodeToString(GetNoteOutput.serializer(), output)
    }

    companion object {
        const val NAME = "get_note"
        const val DESCRIPTION = "Fetch a single note by its ID."
    }
}
