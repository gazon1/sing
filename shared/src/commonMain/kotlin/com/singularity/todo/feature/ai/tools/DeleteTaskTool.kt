package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class DeleteTaskInput(val taskId: String, val reason: String? = null)

@Serializable
data class DeleteTaskOutput(
    val taskId: String,
    val proposalCreated: Boolean,
    val proposalId: String,
    val error: String? = null,
)

/**
 * Creates a soft-delete proposal for a task instead of deleting directly.
 *
 * The actual deletion is deferred until the user confirms the proposal.
 * This prevents accidental destructive actions from the AI chat surface.
 */
class DeleteTaskTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<DeleteTaskInput>(TypeToken.of(DeleteTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteTaskInput): String {
        val result = runCatchingCancellable {
            val taskId = TaskId.fromString(args.taskId)
            val userId = currentUser.scopedUserId.value

            val (proposal, _) = ProposalBuilder.forTask(
                kind = ProposalItemKind.DeleteTask(args.reason),
                taskId = taskId,
                userId = userId,
                source = ProposalSource.Agent,
                clock = clock,
            )

            proposals.save(proposal).getOrThrow()
            proposal.id.value to Unit
        }

        return Json.encodeToString(
            DeleteTaskOutput.serializer(),
            DeleteTaskOutput(
                taskId = args.taskId,
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.first ?: "",
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_task"
        const val DESCRIPTION =
            "Creates a proposal to soft-delete (archive) a task by its ID. " +
                "The deletion requires user confirmation before taking effect."
    }
}
