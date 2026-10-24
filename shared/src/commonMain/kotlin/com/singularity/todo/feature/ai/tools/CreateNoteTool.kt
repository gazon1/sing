package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedNote
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class CreateNoteInput(
    val title: String = "",
    /** Markdown body. Stored as canonical HTML via [NoteContentMapper.toHtml]. */
    val bodyMarkdown: String? = null,
    val isFolder: Boolean = false,
    val parentNoteId: String? = null,
)

/**
 * What came of the request.
 *
 * @property proposalCreated whether a proposal was stored. There is no note id
 *   here: nothing was created, and an id would name a note that does not exist.
 */
@Serializable
data class CreateNoteOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val title: String,
    val error: String? = null,
)

/**
 * Proposes a new note or folder, and lets the user decide whether it should exist.
 *
 * ## Why the body is converted before it is proposed
 *
 * Notes are stored with the body as canonical HTML. A proposal that carries
 * markdown would be applied as markdown and the note would keep it in the legacy
 * field forever. So the body is converted here, once, and the proposal carries
 * the form the note will actually have.
 */
class CreateNoteTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateNoteInput>(TypeToken.of(CreateNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateNoteInput): String {
        val title = args.title.trim()

        // The id the note will have if the user says yes.
        val noteId = NoteId.generate()
        val kind = ProposalItemKind.CreateNote(
            ProposedNote(
                title = title,
                bodyHtml = args.bodyMarkdown?.let { NoteContentMapper.toHtml(it) },
                isFolder = args.isFolder,
                parentNoteId = args.parentNoteId,
            ),
        )

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(listOf(kind), noteId.value),
            targetId = noteId.value,
        )

        return ToolOutputJson.encodeToString(
            CreateNoteOutput.serializer(),
            CreateNoteOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                title = title,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "create_note"

        /**
         * The description the model reads.
         *
         * It says the note is proposed rather than created, because a model that
         * believes it has created a note will say so to the user.
         */
        const val DESCRIPTION =
            "Proposes a new note or folder. The note is not created yet — the user sees it and " +
                "decides. Say that it has been proposed, not that it has been created. " +
                "The body is markdown."
    }
}
