package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class DeleteProjectInput(val projectId: String, val reason: String? = null)

@Serializable
data class DeleteProjectOutput(
    val projectId: String,
    val proposalCreated: Boolean,
    val proposalId: String,
    val error: String? = null,
)

/**
 * Creates a proposal to archive a project instead of deleting directly.
 *
 * The actual deletion is deferred until the user confirms the proposal.
 */
class DeleteProjectTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<DeleteProjectInput>(TypeToken.of(DeleteProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteProjectInput): String {
        val result = runCatchingCancellable {
            ProjectId.fromString(args.projectId) // validate
            val userId = currentUser.scopedUserId.value

            val (proposal, _) = ProposalBuilder.forTarget(
                kind = ProposalItemKind.DeleteProject(args.reason),
                targetKind = AiProposal.TARGET_KIND_PROJECT,
                targetId = args.projectId,
                userId = userId,
                source = ProposalSource.Agent,
                clock = clock,
            )

            proposals.save(proposal).getOrThrow()
            proposal.id.value to Unit
        }

        return Json.encodeToString(
            DeleteProjectOutput.serializer(),
            DeleteProjectOutput(
                projectId = args.projectId,
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.first ?: "",
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_project"
        const val DESCRIPTION =
            "Creates a proposal to archive a project by its ID. " +
                "The deletion requires user confirmation before taking effect."
    }
}
