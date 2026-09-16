package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CreateNoteInput(
    val title: String = "",
    val bodyMarkdown: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: String? = null,
)

@Serializable
data class CreateNoteOutput(val noteId: String, val title: String)

class CreateNoteTool(
    private val notesRepository: NotesRepository,
    private val profileAwareCurrentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateNoteInput>(TypeToken.of(CreateNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateNoteInput): String {
        val now = clock.now()
        val noteId = NoteId.generate()
        val userId = profileAwareCurrentUser.scopedUserId.value
        val note = Note(
            id = noteId,
            title = args.title,
            bodyMarkdown = args.bodyMarkdown,
            isFolder = args.isFolder,
            parentNoteId = args.parentNoteId?.let { NoteId.fromString(it) },
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        notesRepository.create(note)
        return Json.encodeToString(
            CreateNoteOutput.serializer(),
            CreateNoteOutput(noteId.value, note.title),
        )
    }

    companion object {
        const val NAME = "create_note"
        const val DESCRIPTION = "Creates a new note or folder."
    }
}
