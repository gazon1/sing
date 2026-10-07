package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class CreateNoteInput(
    val title: String = "",
    /** Markdown body. Stored as canonical HTML via [NoteContentMapper.toHtml]. */
    val bodyMarkdown: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: String? = null,
)

@Serializable
data class CreateNoteOutput(val noteId: String, val title: String)

/**
 * Creates a note or folder.
 *
 * ## Why the create is unwrapped
 *
 * `notesRepository.create` returns a `Result`, and discarding it is not a style
 * choice here: the id below is generated *before* the write, so a discarded
 * failure produces byte-identical output to a success — `{"noteId": "<an id
 * naming nothing>", "title": "..."}`. The model's only evidence about what
 * happened is that payload, so it goes on to open, link and cite a note that does
 * not exist. `.getOrThrow()` makes the tool call fail, which is the failure shape
 * the four `Delete*Tool` in this package already use.
 *
 * Every create/update tool here follows this. See openspec change
 * `a-write-tool-reports-the-write-it-did-not-perform`.
 */
class CreateNoteTool(
    private val notesRepository: NotesRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : SimpleTool<CreateNoteInput>(TypeToken.of(CreateNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateNoteInput): String {
        val now = clock.now()
        val noteId = NoteId.generate()
        val userId = currentUser.scopedUserId.value
        // Convert markdown to canonical HTML: notes are stored with bodyHtml as the
        // canonical representation. Without this, AI-created notes have bodyMarkdown
        // set but bodyHtml=null, and the editor falls back to toHtml() on open —
        // which works but stores the markdown in the legacy field forever.
        val bodyHtml = args.bodyMarkdown?.let { NoteContentMapper.toHtml(it) }
        val note = Note(
            id = noteId,
            title = args.title,
            bodyMarkdown = null, // markdown stored only as HTML
            bodyHtml = bodyHtml,
            isFolder = args.isFolder,
            parentNoteId = args.parentNoteId?.let { NoteId.fromString(it) },
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        notesRepository.create(note).getOrThrow()
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
