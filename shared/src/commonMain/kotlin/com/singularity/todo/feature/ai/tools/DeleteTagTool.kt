package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tags.TagId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class DeleteTagInput(val tagId: String, val reason: String? = null)

@Serializable
data class DeleteTagOutput(
    val tagId: String,
    val proposalCreated: Boolean,
    val proposalId: String,
    val error: String? = null,
)

/**
 * Creates a proposal to soft-delete a tag instead of deleting directly.
 *
 * The actual deletion is deferred until the user confirms the proposal.
 */
class DeleteTagTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<DeleteTagInput>(TypeToken.of(DeleteTagInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteTagInput): String {
        val result = runCatchingCancellable {
            TagId.fromString(args.tagId) // validate
            val userId = currentUser.scopedUserId.value

            val (proposal, _) = ProposalBuilder.forTarget(
                kind = ProposalItemKind.DeleteTag(args.reason),
                targetId = args.tagId,
                userId = userId,
                source = ProposalSource.Agent,
                clock = clock,
            )

            proposals.save(proposal).getOrThrow()
            proposal.id.value to Unit
        }

        return Json.encodeToString(
            DeleteTagOutput.serializer(),
            DeleteTagOutput(
                tagId = args.tagId,
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.first ?: "",
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_tag"
        const val DESCRIPTION =
            "Creates a proposal to soft-delete a tag by its ID. " +
                "The deletion requires user confirmation before taking effect."
    }
}
