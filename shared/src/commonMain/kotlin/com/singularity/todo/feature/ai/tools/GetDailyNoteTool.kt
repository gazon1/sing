package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.serialization.Serializable

@Serializable
data class GetDailyNoteInput(val dateKey: String, val projectId: String? = null)

@Serializable
data class GetDailyNoteOutput(val id: String?, val title: String?, val bodyMarkdown: String?)

class GetDailyNoteTool(private val notesRepo: NotesRepository) :
    SimpleTool<GetDailyNoteInput>(TypeToken.of(GetDailyNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GetDailyNoteInput): String {
        val pid = args.projectId?.let { com.singularity.todo.feature.projects.domain.model.ProjectId.fromString(it) }
        val note = notesRepo.getDailyNote(args.dateKey, pid)
        val output = if (note != null) {
            GetDailyNoteOutput(note.id.value, note.title, note.bodyMarkdown)
        } else {
            GetDailyNoteOutput(null, null, null)
        }
        return kotlinx.serialization.json.Json.encodeToString(GetDailyNoteOutput.serializer(), output)
    }

    companion object {
        const val NAME = "get_daily_note"
        const val DESCRIPTION = "Fetch the daily standup note for a date (ISO YYYY-MM-DD), if one exists."
    }
}