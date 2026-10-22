package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.logic.ProposalOperation
import com.singularity.todo.feature.proposals.domain.logic.humanSummary
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * A change to a task, proposed rather than performed.
 *
 * @property taskId the task the change applies to.
 * @property operation what to change. One of [ProposalOperation.NAMES].
 * @property value the new value, for the operations that set a field.
 * @property items the entries to add or remove, for the operations that take a list.
 * @property reason why the change is proposed. The user reads it before deciding.
 */
@Serializable
data class WriteTaskProposalInput(
    val taskId: String,
    val operation: String,
    val value: String? = null,
    val items: List<String> = emptyList(),
    val reason: String? = null,
)

/**
 * What came of the proposal.
 *
 * @property proposalCreated whether a proposal was actually stored.
 * @property summary the line the user sees in the confirmation screen.
 */
@Serializable
data class WriteTaskProposalOutput(
    val taskId: String,
    val proposalId: String,
    val proposalCreated: Boolean,
    val summary: String,
    val error: String? = null,
)

/**
 * Proposes a change to a task, and lets the user decide.
 *
 * ## Why a proposal and not a write
 *
 * The write tools write. That is right for a tool the user asked to change something
 * and can see the result — but not for a tool an agent reached for on its own
 * initiative, mid-conversation, to decide that a task ought to have a different due
 * date. The user did not ask for that, and the change would land without anyone
 * having looked at it.
 *
 * A proposal is the change held until someone looks at it. The machinery for that
 * already exists — [ProposalRepository], [ProposalBuilder], and the apply use case
 * that validates before it claims — so this tool joins that machinery rather than
 * inventing a second kind of "pending change".
 *
 * ## What it does not do
 *
 * It does not apply the change, and it does not decide whether the change is a good
 * idea. It stores the change and reports where the user will see it.
 */
class WriteTaskProposalTool(
    private val proposals: ProposalRepository,
    private val taskRepository: TaskRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<WriteTaskProposalInput>(TypeToken.of(WriteTaskProposalInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: WriteTaskProposalInput): String {
        val operation = args.operation.trim().lowercase()

        validate(args, operation)?.let { problem -> return failure(args, problem) }

        val kind = ProposalOperation.toItemKind(operation, args.value, args.items)
            ?: return failure(args, "The operation \"$operation\" could not be turned into a change.")

        val taskId = TaskId.fromString(args.taskId)
        if (taskRepository.get(taskId) == null) {
            return failure(args, "There is no task with id \"${args.taskId}\".")
        }

        val result = runCatchingCancellable {
            val userId = currentUser.scopedUserId.value
            val item = ProposalItem(
                id = ProposalItemId.generate(),
                proposalId = ProposalId.generate(),
                kind = kind,
                targetId = taskId.value,
                humanSummary = kind.humanSummary,
                status = ProposalItemStatus.Pending,
                fingerprint = ProposalFingerprint.of(kind, taskId.value),
                sortOrder = 0,
            )

            val (proposal, _) = ProposalBuilder.multiForTask(
                items = listOf(item),
                taskId = taskId,
                userId = userId,
                source = ProposalSource.Agent,
                clock = clock,
            )

            proposals.save(proposal).getOrThrow()
            proposal
        }

        val proposal = result.getOrNull()
        return ToolOutputJson.encodeToString(
            WriteTaskProposalOutput.serializer(),
            WriteTaskProposalOutput(
                taskId = args.taskId,
                proposalId = proposal?.id?.value.orEmpty(),
                proposalCreated = proposal != null,
                summary = summary(kind.humanSummary, args.reason),
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    /**
     * What is wrong with the request, or null when nothing is.
     *
     * The message names the operations that exist, or the thing that is missing, so
     * the model can fix the call and try again instead of guessing.
     */
    private fun validate(args: WriteTaskProposalInput, operation: String): String? = when {
        operation !in ProposalOperation.NAMES ->
            "Unknown operation \"${args.operation}\". Allowed: ${ProposalOperation.NAMES.joinToString(", ")}."

        ProposalOperation.needsValue(operation) && args.value.isNullOrBlank() ->
            "The operation \"$operation\" needs a value."

        ProposalOperation.needsItems(operation) && args.items.isEmpty() ->
            "The operation \"$operation\" needs at least one item."

        else -> null
    }

    /**
     * The line the user reads.
     *
     * The reason is part of it: a proposal the user cannot see the point of is a
     * proposal the user will decline without reading.
     */
    private fun summary(what: String, reason: String?): String =
        if (reason.isNullOrBlank()) what else "$what — $reason"

    private fun failure(args: WriteTaskProposalInput, message: String): String = ToolOutputJson.encodeToString(
        WriteTaskProposalOutput.serializer(),
        WriteTaskProposalOutput(
            taskId = args.taskId,
            proposalId = "",
            proposalCreated = false,
            summary = "",
            error = message,
        ),
    )

    companion object {
        const val NAME = "write_task_proposal"

        /**
         * The description the model reads.
         *
         * It says that the change is proposed rather than performed, because a model
         * that believes it has changed the task will say so to the user.
         */
        const val DESCRIPTION = """
            Propose a change to a task. The change is not applied yet — the user sees it
            and decides. Say that it has been proposed, not that it has been done.

            The operation is one of: set_title, set_description, set_priority,
            set_due_date, set_estimate, set_status, add_tags, remove_tags,
            add_checklist, add_subtasks.
            - set_* takes the new value in "value".
            - add_* and remove_* take one entry per item in "items".
            - set_due_date takes a date as YYYY-MM-DD. set_estimate takes minutes.
            - set_status takes "active" or "completed".

            The reason is shown to the user with the change, so say why the change is
            being proposed.
        """
    }
}
