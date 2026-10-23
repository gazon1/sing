package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.core.database.LocalTimeFormats
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class UpdateTaskInput(
    val taskId: String,
    val title: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val kind: String? = null,
    val projectId: String? = null,
    val tagIds: List<String>? = null,
    val dueDate: String? = null, // "YYYY-MM-DD" or null to clear
    val dueTime: String? = null, // "HH:mm" or null to clear
    val someday: Boolean? = null,
)

/**
 * What came of the request.
 *
 * @property proposalCreated whether a proposal was stored. There is no "updated"
 *   here: nothing was updated, and a flag that says otherwise would be a lie the
 *   model would repeat to the user.
 */
@Serializable
data class UpdateTaskOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val taskId: String,
    val error: String? = null,
)

/**
 * Proposes a change to a task, and lets the user decide.
 *
 * ## Why it proposes the difference and not the whole task
 *
 * A proposal that carries the whole task says "the task should be exactly this" —
 * which is a different proposal from "the title should be this". So the tool
 * reads the task, works out what actually differs, and proposes *that*: one item
 * per changed field.
 *
 * The user then reads a list of changes ("Update title", "Set priority") instead
 * of a task that has to be compared with the one they already have.
 *
 * ## What it does not do
 *
 * It does not apply the change, and it does not decide whether the change is a
 * good idea.
 */
class UpdateTaskTool(
    private val proposals: ProposalRepository,
    private val taskRepository: TaskRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<UpdateTaskInput>(TypeToken.of(UpdateTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: UpdateTaskInput): String {
        val existing = taskRepository.observe(TaskId(args.taskId)).first()
            ?: return failure(args.taskId, "There is no task with id \"${args.taskId}\".")

        val changes = changes(args, existing)
        val kinds = when (changes) {
            is Changes.Invalid -> return failure(args.taskId, changes.message)
            is Changes.None -> return failure(args.taskId, NOTHING_TO_CHANGE)
            is Changes.Some -> changes.kinds
        }

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(kinds, args.taskId),
            targetKind = AiProposal.TARGET_KIND_TASK,
            targetId = args.taskId,
        )

        return ToolOutputJson.encodeToString(
            UpdateTaskOutput.serializer(),
            UpdateTaskOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                taskId = args.taskId,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    /**
     * The changes the request asks for.
     *
     * The values are checked here, in the tool, rather than at apply time: a
     * proposal the user approves and then cannot apply is a proposal that burned
     * the user's attention for nothing. A value that does not parse is reported
     * to the model, which can fix the call.
     */
    private fun changes(args: UpdateTaskInput, existing: Task): Changes {
        val outcomes = listOf(
            titleChange(args.title, existing),
            descriptionChange(args.description, existing),
            priorityChange(args.priority, existing),
            kindChange(args.kind, existing),
            projectChange(args.projectId, existing),
            tagsChange(args.tagIds, existing),
            dueDateChange(args.dueDate, existing),
            dueTimeChange(args.dueTime, existing),
            somedayChange(args.someday, existing),
        )
        outcomes.filterIsInstance<FieldChange.Invalid>().firstOrNull()?.let { return Changes.Invalid(it.message) }
        val kinds = outcomes.filterIsInstance<FieldChange.Add>().map { it.kind }
        return if (kinds.isEmpty()) Changes.None else Changes.Some(kinds)
    }

    /**
     * One field of the request, read through the vocabulary.
     *
     * The tool does not know how a priority is spelled — the vocabulary does, and it
     * is the same reader the planner will use when the change is applied. So a value
     * the model named wrong is refused here, where the model can fix the call, and a
     * value that reads is stored in the spelling the task holds.
     *
     * The comparison is against the value's stored text, which is the same text that
     * would be written: a request that asks for what the task already has is not a
     * change, and there is nothing to propose.
     */
    private fun fieldChange(value: String?, field: String, current: String?): FieldChange {
        if (value == null) return FieldChange.Skip
        return when (val read = TaskValue.read(field, value)) {
            is ReadResult.Ok ->
                if (read.value.text == current) {
                    FieldChange.Skip
                } else {
                    FieldChange.Add(ProposalItemKind.SetTaskField(read.value))
                }

            is ReadResult.Rejected -> FieldChange.Invalid(read.reason)
        }
    }

    private fun titleChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "Title", existing.title)

    private fun descriptionChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "Description", existing.description)

    private fun priorityChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "Priority", existing.priority.name)

    private fun kindChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "Kind", existing.kind.name)

    private fun projectChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "Project", existing.projectId?.value)

    private fun tagsChange(value: List<String>?, existing: Task): FieldChange {
        if (value == null) return FieldChange.Skip
        if (value.toSet() == existing.tags.map { it.value }.toSet()) return FieldChange.Skip
        return FieldChange.Add(ProposalItemKind.SetTaskTags(value))
    }

    private fun dueDateChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "DueDate", existing.dueDate?.toString())

    private fun dueTimeChange(value: String?, existing: Task): FieldChange =
        fieldChange(value, "DueTime", existing.dueTime?.let { LocalTimeFormats.format(it) })

    private fun somedayChange(value: Boolean?, existing: Task): FieldChange {
        if (value == null || value == existing.someday) return FieldChange.Skip
        return FieldChange.Add(ProposalItemKind.SetTaskField(TaskValue.Someday(value)))
    }

    /** What one field of the request asks for. */
    private sealed interface FieldChange {
        data class Add(val kind: ProposalItemKind) : FieldChange

        data object Skip : FieldChange

        data class Invalid(val message: String) : FieldChange
    }

    /** What the request asks for: some changes, none, or a value that does not parse. */
    private sealed interface Changes {
        data class Some(val kinds: List<ProposalItemKind>) : Changes

        data object None : Changes

        data class Invalid(val message: String) : Changes
    }

    private fun failure(taskId: String, message: String): String = ToolOutputJson.encodeToString(
        UpdateTaskOutput.serializer(),
        UpdateTaskOutput(proposalCreated = false, proposalId = "", taskId = taskId, error = message),
    )

    companion object {
        const val NAME = "update_task"

        /** What the tool says when the request asks for what the task already has. */
        private const val NOTHING_TO_CHANGE =
            "The task already has these values — there is nothing to change."

        /**
         * The description the model reads.
         *
         * It says the change is proposed rather than performed, because a model
         * that believes it has changed the task will say so to the user.
         */
        const val DESCRIPTION =
            "Proposes a change to a task. The change is not applied yet — the user sees it and " +
                "decides. Say that it has been proposed, not that it has been done. " +
                "Pass only the fields to change; a field left out is not touched. " +
                "Priority is none, low, medium, high or urgent. dueDate is YYYY-MM-DD, an empty " +
                "string clears it. dueTime is HH:mm, an empty string clears it. " +
                "tagIds replaces the whole set of tags."
    }
}
