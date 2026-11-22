package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
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
    /**
     * The `updatedAt` (epoch millis) the caller read the note at, when the change is a
     * refresh of an existing brief.
     *
     * A refresh re-reads a source and proposes what changed. If the user edited the
     * note in between, the model's summary was written against a note that no longer
     * exists, and applying it would overwrite the edit. So the caller states what it
     * read, and a note that has moved since is refused before anything is proposed.
     */
    val expectedUpdatedAt: Long? = null,
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
        val existing =
            notesRepository.get(NoteId(args.noteId))
                ?: return failure(args.noteId, "There is no note with id \"${args.noteId}\".")

        val kinds = changes(args, existing)
        refusal(args, existing, kinds)?.let { return it }

        val result =
            proposeChange(
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
                proposalId =
                    result
                        .getOrNull()
                        ?.id
                        ?.value
                        .orEmpty(),
                noteId = args.noteId,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    /**
     * Why this request cannot become a proposal, or `null` when it can.
     *
     * The reasons are one function so that [execute] keeps a single refusal path: a tool
     * that reads, checks and writes has several ways to say no, and each `return` in the
     * body is another exit a reader has to hold.
     */
    private fun refusal(
        args: UpdateNoteInput,
        existing: Note,
        kinds: List<ProposalItemKind>,
    ): String? {
        // A brief is refreshed from a read, so the read is what an update has to state.
        // Without the version there is no guard at all — the proposal would be applied to
        // whatever the note has become by the time the user confirms it, which is the
        // failure this tool exists to avoid. Ordinary in-app editing does not come
        // through here; it has its own path.
        if (KnowledgeBriefShape.isBrief(existing) && args.expectedUpdatedAt == null) {
            return failure(
                args.noteId,
                "An update to a knowledge brief states the version it was read at: read it " +
                    "with get_knowledge_brief and pass its updatedAt as expectedUpdatedAt.",
            )
        }

        // The note moved since the caller read it: what it wrote about is not what is
        // here any more. Refused before the proposal, so the user's edit is never
        // silently replaced by a summary of an older note.
        val expected = args.expectedUpdatedAt
        val actual = existing.updatedAt.toEpochMilliseconds()
        if (expected != null && expected != actual) {
            return failure(
                args.noteId,
                "The note changed since it was read (updatedAt $actual, expected $expected). " +
                    "Read it again and propose the change against what is there now.",
            )
        }

        return if (kinds.isEmpty()) {
            failure(args.noteId, "The note already has these values — there is nothing to change.")
        } else {
            null
        }
    }

    /**
     * The change the request asks for: one item carrying every field that really
     * differs.
     *
     * One item, not one per field. An update that renames a note and rewrites its body
     * is one act, and the user decides about it once — and split into items, the first
     * field's write would advance the note's version and the second would then look
     * stale, so the confirmed update would apply in part.
     */
    private fun changes(
        args: UpdateNoteInput,
        existing: Note,
    ): List<ProposalItemKind> {
        val values = mutableListOf<NoteValue>()

        args.title?.let { value ->
            if (value.trim() != existing.title.trim()) {
                // The version travels with the change, so the check holds at the moment
                // the user confirms it and not only at the moment the model asked. See
                // `ProposalPlanner.withNoteValues`.
                values += NoteValue.Title(value, expectedUpdatedAt = args.expectedUpdatedAt)
            }
        }

        // Markdown first: a caller that sends markdown means the note's text, and a
        // caller that sends HTML means the note's markup. The note is stored as HTML
        // either way.
        val body = args.bodyMarkdown?.let { NoteContentMapper.toHtml(it) } ?: args.bodyHtml
        body?.let { value ->
            if (value != existing.bodyHtml) {
                values += NoteValue.Body(value, expectedUpdatedAt = args.expectedUpdatedAt)
            }
        }

        return if (values.isEmpty()) emptyList() else listOf(ProposalItemKind.SetNoteFields(values))
    }

    private fun failure(
        noteId: String,
        message: String,
    ): String =
        ToolOutputJson.encodeToString(
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
                "The body is markdown unless bodyHtml is given. " +
                "When refreshing a note you read earlier, pass expectedUpdatedAt from that read: " +
                "a note that changed since then is refused instead of overwritten."
    }
}
