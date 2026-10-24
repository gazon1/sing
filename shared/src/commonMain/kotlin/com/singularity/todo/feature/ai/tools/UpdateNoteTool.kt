package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class UpdateNoteInput(
    val noteId: String,
    val title: String? = null,
    val bodyMarkdown: String? = null,
    val bodyHtml: String? = null,
)

/**
 * What came of the request.
 *
 * @property proposalCreated whether a proposal was stored. There is no "updated"
 *   here: nothing was updated, and a flag that says otherwise would be a lie the
 *   model would repeat to the user.
 */
@Serializable
data class UpdateNoteOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val noteId: String,
    val error: String? = null,
)

/**
 * Proposes a change to a note, and lets the user decide.
 *
 * The body is proposed as the canonical HTML the note will carry, because that is
 * the form the note is stored in — a proposal that carried markdown would be
 * applied as markdown.
 */
class UpdateNoteTool(
    private val proposals: ProposalRepository,
    private val notesRepository: NotesRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<UpdateNoteInput>(TypeToken.of(UpdateNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: UpdateNoteInput): String {
        val existing = notesRepository.get(NoteId(args.noteId))
            ?: return failure(args.noteId, "There is no note with id \"${args.noteId}\".")

        val kinds = changes(args, existing)
        if (kinds.isEmpty()) {
            return failure(args.noteId, "The note already has these values — there is nothing to change.")
        }

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(kinds, args.noteId),
            targetId = args.noteId,
        )

        return ToolOutputJson.encodeToString(
            UpdateNoteOutput.serializer(),
            UpdateNoteOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                noteId = args.noteId,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    /** The changes the request asks for: one item per field that actually differs. */
    private fun changes(args: UpdateNoteInput, existing: Note): List<ProposalItemKind> {
        val kinds = mutableListOf<ProposalItemKind>()

        args.title?.let { value ->
            if (value.trim() != existing.title.trim()) {
                kinds += ProposalItemKind.SetNoteField(NoteValue.Title(value))
            }
        }

        // Markdown first: a caller that sends markdown means the note's text, and a
        // caller that sends HTML means the note's markup. The note is stored as HTML
        // either way.
        val body = args.bodyMarkdown?.let { NoteContentMapper.toHtml(it) } ?: args.bodyHtml
        body?.let { value ->
            if (value != existing.bodyHtml) {
                kinds += ProposalItemKind.SetNoteField(NoteValue.Body(value))
            }
        }

        return kinds
    }

    private fun failure(noteId: String, message: String): String = ToolOutputJson.encodeToString(
        UpdateNoteOutput.serializer(),
        UpdateNoteOutput(proposalCreated = false, proposalId = "", noteId = noteId, error = message),
    )

    companion object {
        const val NAME = "update_note"

        /**
         * The description the model reads.
         *
         * It says the change is proposed rather than performed, because a model
         * that believes it has changed the note will say so to the user.
         */
        const val DESCRIPTION =
            "Proposes a change to a note's title and/or body. The change is not applied yet — the " +
                "user sees it and decides. Say that it has been proposed, not that it has been done. " +
                "Pass only what to change; a field left out is not touched. " +
                "The body is markdown unless bodyHtml is given."
    }
}
