package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class CreateTaskInput(
    val title: String,
    val description: String? = null,
    val priority: String = "None",
    val kind: String = "Task",
    val projectId: String? = null,
    val parentTaskId: String? = null,
    val tagIds: List<String> = emptyList(),
    val dueDate: String? = null, // "YYYY-MM-DD"
    val dueTime: String? = null, // "HH:mm"
    val someday: Boolean = false,
)

/**
 * What came of the request.
 *
 * @property proposalCreated whether a proposal was stored. There is no task id
 *   here: nothing was created, and an id would name a task that does not exist.
 */
@Serializable
data class CreateTaskOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val title: String,
    val error: String? = null,
)

/**
 * Proposes a new task, and lets the user decide whether it should exist.
 *
 * ## Why a proposal and not a task
 *
 * A task the assistant wrote on its own is a task the user never saw. The user
 * asked for something — a plan, an answer, a summary — and the assistant's
 * reading of that request became a row in their list. The same act, in the
 * delete tools, is a proposal: the assistant says what it would remove, and the
 * user says whether it goes.
 *
 * So creating a task is the same act as removing one, and it gets the same
 * treatment. The proposal holds the task until the user confirms it; the
 * confirmation screen is where the task's fields are read.
 *
 * ## What it does not do
 *
 * It does not create the task, and it does not decide whether the task is a good
 * idea. The model of a task is the model's; the user reads it and says yes or no.
 */
class CreateTaskTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateTaskInput>(TypeToken.of(CreateTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateTaskInput): String {
        val title = args.title.trim()
        if (title.isEmpty()) {
            return failure(title, "A task needs a title.")
        }

        // The values the task will hold, spelled the way the task holds them.
        // A name the model invented is reported here, where the model can fix
        // the call — not later, when the user approves a proposal that cannot
        // be applied.
        val priority = canonicalPriority(args.priority)
        if (priority == null) {
            return failure(title, "\"${args.priority.trim()}\" is not a priority. $PRIORITY_NAMES")
        }
        val taskKind = canonicalKind(args.kind)
        if (taskKind == null) {
            return failure(title, "\"${args.kind.trim()}\" is not a kind of work. $KIND_NAMES")
        }

        // The id the task will have if the user says yes. Naming it now means the
        // proposal and the task agree on what it is.
        val taskId = TaskId.generate()
        val kind = ProposalItemKind.CreateTask(
            ProposedTask(
                title = title,
                description = args.description,
                priority = priority.name,
                kind = taskKind.name,
                projectId = args.projectId,
                parentTaskId = args.parentTaskId,
                tagIds = args.tagIds,
                dueDate = args.dueDate,
                dueTime = args.dueTime,
                someday = args.someday,
            ),
        )

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(listOf(kind), taskId.value),
            targetKind = AiProposal.TARGET_KIND_TASK,
            targetId = taskId.value,
        )

        return ToolOutputJson.encodeToString(
            CreateTaskOutput.serializer(),
            CreateTaskOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                title = title,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    private fun failure(title: String, message: String): String = ToolOutputJson.encodeToString(
        CreateTaskOutput.serializer(),
        CreateTaskOutput(proposalCreated = false, proposalId = "", title = title, error = message),
    )

    companion object {
        const val NAME = "create_task"

        /**
         * The description the model reads.
         *
         * It says the task is proposed rather than created, because a model that
         * believes it has created a task will tell the user so.
         */
        const val DESCRIPTION =
            "Proposes a new task. The task is not created yet — the user sees it and decides. " +
                "Say that it has been proposed, not that it has been created. " +
                "All fields optional except title. Priority is none, low, medium, high or urgent. " +
                "dueDate is YYYY-MM-DD, dueTime is HH:mm."
    }
}
