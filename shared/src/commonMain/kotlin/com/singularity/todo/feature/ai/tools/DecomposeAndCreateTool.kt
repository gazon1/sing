package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.ai.prompts.Prompts
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Companion to [DecomposeTaskTool] that does both steps in one call: asks the LLM
 * for a sub-task plan, then proposes each sub-task.
 *
 * Why a separate tool:
 * - [DecomposeTaskTool] is intentionally plan-only (returns a JSON list and exits);
 *   callers have to repeat the orchestration of [CreateTaskTool] per sub-task.
 *   That pattern failed at least once in dogfooding when the LLM was down and the
 *   caller had to remember to fall back to a hand-rolled list.
 * - [DecomposeAndCreateTool] consolidates the workflow: one call, one proposal
 *   holding every sub-task. The tool itself handles the "no LLM available"
 *   fallback by proposing nothing, so callers can detect that case from the
 *   response and provide a manual fallback at the agent level.
 *
 * Input fields mirror [CreateTaskTool] so the proposed sub-tasks inherit the same
 * project / tag / priority context as the parent.
 *
 * ## Why it proposes and does not write
 *
 * A decomposition is the model's reading of a task, and a reading the user never
 * saw is not something to write into their list. So the sub-tasks are proposed,
 * the way a single created task is proposed, and the user decides about all of
 * them at once.
 */
@Serializable
data class DecomposeAndCreateInput(
    val title: String,
    val description: String? = null,
    val priority: String = "Medium",
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val parentTaskId: String? = null,
)

/**
 * What came of the request.
 *
 * @property proposedTaskCount how many tasks the proposal holds. Nothing was
 *   created, so there are no task ids to report: an id naming a task that does
 *   not exist would be a fact the model could not check.
 */
@Serializable
data class DecomposeAndCreateOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val proposedTaskCount: Int,
    val planSource: String, // "llm" | "empty"
    val error: String? = null,
)

class DecomposeAndCreateTool(
    private val proposals: ProposalRepository,
    private val clock: Clock,
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val currentUser: ProfileAwareCurrentUser,
) : SimpleTool<DecomposeAndCreateInput>(
        TypeToken.of(DecomposeAndCreateInput::class.java),
        NAME,
        DESCRIPTION,
    ) {

    override suspend fun execute(args: DecomposeAndCreateInput): String {
        val userId = currentUser.scopedUserId.value

        // 1) plan via LLM
        val (subTitles, planSource) = planSubtitles(args)

        // 2) propose each sub-task. The ids are the ids the tasks *will* have, so
        // the proposal and the tasks agree on what they are.
        val priority = when (val read = TaskValue.readPriority(args.priority)) {
            is ReadResult.Ok -> read.value

            is ReadResult.Rejected -> {
                return ToolOutputJson.encodeToString(
                    DecomposeAndCreateOutput.serializer(),
                    DecomposeAndCreateOutput(
                        proposalCreated = false,
                        proposalId = "",
                        proposedTaskCount = 0,
                        planSource = planSource,
                        error = read.reason,
                    ),
                )
            }
        }
        val kinds = subTitles.map { title ->
            ProposalItemKind.CreateTask(
                ProposedTask(
                    title = title,
                    description = null,
                    priority = priority,
                    kind = TaskKind.Task,
                    projectId = args.projectId,
                    parentTaskId = args.parentTaskId,
                    tagIds = args.tagIds,
                ),
            )
        }
        val ids = subTitles.map { TaskId.generate().value }
        val items = proposalItems(kinds, ids.firstOrNull().orEmpty())
            .mapIndexed { index, item -> item.copy(targetId = ids[index]) }

        if (items.isEmpty()) {
            return ToolOutputJson.encodeToString(
                DecomposeAndCreateOutput.serializer(),
                DecomposeAndCreateOutput(
                    proposalCreated = false,
                    proposalId = "",
                    proposedTaskCount = 0,
                    planSource = planSource,
                    error = "The plan was empty, so there is nothing to propose.",
                ),
            )
        }

        val result = proposeChange(
            proposals = proposals,
            userId = userId,
            clock = clock,
            items = items,
            targetKind = AiProposal.TARGET_KIND_TASK,
            targetId = args.parentTaskId ?: ids.first(),
        )

        return ToolOutputJson.encodeToString(
            DecomposeAndCreateOutput.serializer(),
            DecomposeAndCreateOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                proposedTaskCount = items.size,
                planSource = planSource,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    private fun parsePlan(text: String): List<String> = runCatching {
        Json.decodeFromString<DecomposeTaskOutput>(text).subTasks
    }.getOrElse {
        text.lines()
            .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
            .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
    }

    private fun extractText(response: Message.Assistant): String =
        response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()

    companion object {
        const val NAME = "decompose_and_create"
        const val DESCRIPTION =
            "Asks the LLM to break a task into sub-tasks, then proposes each sub-task in one call. " +
                "The sub-tasks are proposed, not created — the user sees them and decides. " +
                "planSource='llm' or 'empty' (LLM unavailable)."
    }

    /**
     * The subtasks the plan names, and where the plan came from.
     *
     * When the model cannot be reached — no API key, the network is down, the quota
     * is spent — the plan is empty rather than an exception: the caller then knows to
     * fall back, and the agent can keep going.
     */
    private suspend fun planSubtitles(args: DecomposeAndCreateInput): Pair<List<String>, String> = try {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.decomposeTaskSystem)
            user(Prompts.decomposeTaskUser(args.title, args.description))
        }
        val text = extractText(promptExecutor.execute(p, model, emptyList()))
        val parsed = parsePlan(text)
        parsed to if (parsed.isEmpty()) "empty" else "llm"
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList<String>() to "empty"
    }
}
