package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

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
            val proposalId = ProposalId.generate()
            val itemId = ProposalItemId.generate()
            val now = clock.now()
            val userId = currentUser.scopedUserId.value

            val item = ProposalItem(
                id = itemId,
                proposalId = proposalId,
                kind = ProposalItemKind.DeleteNote(args.reason),
                targetId = args.noteId,
                humanSummary = "Delete note",
                status = ProposalItemStatus.Pending,
                fingerprint = "",
                sortOrder = 0,
            )

            val proposal = AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_NOTE,
                targetId = args.noteId,
                userId = userId,
                source = ProposalSource.Agent,
                status = ProposalStatus.Pending,
                createdAt = now,
                updatedAt = now,
                items = listOf(item),
            )

            proposals.save(proposal).getOrThrow()
            proposalId.value to Unit
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
