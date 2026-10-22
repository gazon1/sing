package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class DeleteNoteInput(val noteId: String, val reason: String? = null)

@Serializable
data class DeleteNoteOutput(
    val noteId: String,
    val proposalCreated: Boolean,
    val proposalId: String,
    val error: String? = null,
)

/**
 * Creates a proposal to soft-delete a note instead of deleting directly.
 *
 * The actual deletion is deferred until the user confirms the proposal.
 */
class DeleteNoteTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<DeleteNoteInput>(TypeToken.of(DeleteNoteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteNoteInput): String {
        val result = runCatchingCancellable {
            NoteId.fromString(args.noteId) // validate
            val userId = currentUser.scopedUserId.value

            val (proposal, _) = ProposalBuilder.forNote(
                kind = ProposalItemKind.DeleteNote(args.reason),
                noteId = args.noteId,
                userId = userId,
                source = ProposalSource.Agent,
                clock = clock,
            )

            proposals.save(proposal).getOrThrow()
            proposal.id.value to Unit
        }

        return Json.encodeToString(
            DeleteNoteOutput.serializer(),
            DeleteNoteOutput(
                noteId = args.noteId,
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.first ?: "",
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_note"
        const val DESCRIPTION =
            "Creates a proposal to soft-delete a note by its ID. " +
                "The deletion requires user confirmation before taking effect."
    }
}
