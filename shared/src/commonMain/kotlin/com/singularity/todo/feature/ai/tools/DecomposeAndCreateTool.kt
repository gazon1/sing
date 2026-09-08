package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.ai.prompts.Prompts
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskKind
import com.singularity.todo.feature.tasks.TaskPriority
import com.singularity.todo.feature.tasks.TaskRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Companion to [DecomposeTaskTool] that does both steps in one call: asks the LLM
 * for a sub-task plan, then writes each sub-task to the DB.
 *
 * Why a separate tool:
 * - [DecomposeTaskTool] is intentionally plan-only (returns a JSON list and exits);
 *   callers have to repeat the orchestration of [CreateTaskTool] per sub-task.
 *   That pattern failed at least once in dogfooding when the LLM was down and the
 *   caller had to remember to fall back to a hand-rolled list.
 * - [DecomposeAndCreateTool] consolidates the workflow: one call, one response
 *   listing the created taskIds. The tool itself handles the "no LLM available"
 *   fallback by returning an empty list, so callers can detect that case from
 *   the response and provide a manual fallback at the agent level.
 *
 * Input fields mirror [CreateTaskTool] so the created sub-tasks inherit the same
 * project / tag / priority context as the parent.
 *
 * @param parentTaskId Optional id of the parent task. When set, future versions
 *        (Task entity v10) will persist it on each sub-task. Today the field is
 *        surfaced for forward-compatibility but is **not** yet written to DB — the
 *        parent-child link exists only via the shared projectId / tagIds.
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

@Serializable
data class DecomposeAndCreateOutput(
    val parentTaskId: String?,
    val subTaskIds: List<String>,
    val planSource: String, // "llm" | "empty"
)

class DecomposeAndCreateTool(
    private val taskRepository: TaskRepository,
    private val profileAwareCurrentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
) : SimpleTool<DecomposeAndCreateInput>(
    TypeToken.of(DecomposeAndCreateInput::class.java), NAME, DESCRIPTION,
) {

    override suspend fun execute(args: DecomposeAndCreateInput): String {
        val now = clock.now()
        val userId = profileAwareCurrentUser.scopedUserId.value

        // 1) plan via LLM
        var subTitles: List<String> = emptyList()
        var planSource = "empty"
        try {
            val p = prompt(Prompt.Empty, KoogClock.System) {
                system(Prompts.decomposeTaskSystem)
                user(Prompts.decomposeTaskUser(args.title, args.description))
            }
            val text = extractText(promptExecutor.execute(p, model, emptyList()))
            val parsed = parsePlan(text)
            subTitles = parsed
            planSource = if (parsed.isEmpty()) "empty" else "llm"
        } catch (_: Throwable) {
            // LLM unavailable (no API key, network down, quota). Behave like
            // decompose_task itself: return an empty list so the caller knows
            // to fall back. We do NOT throw — the MCP agent can keep going.
            subTitles = emptyList()
            planSource = "empty"
        }

        // 2) create each sub-task
        val subIds = subTitles.map { title ->
            val subId = TaskId.generate()
            taskRepository.create(
                Task(
                    id = subId,
                    title = title,
                    description = null,
                    priority = runCatching { TaskPriority.valueOf(args.priority) }.getOrDefault(TaskPriority.Medium),
                    kind = TaskKind.Task,
                    projectId = args.projectId?.let { ProjectId.fromString(it) },
                    parentTaskId = args.parentTaskId?.let { TaskId.fromString(it) },
                    tags = args.tagIds.map { TagId.fromString(it) },
                    dueDate = null,
                    dueTime = null,
                    someday = false,
                    createdAt = now,
                    updatedAt = now,
                    userId = userId,
                ),
            )
            subId.value
        }

        val out = DecomposeAndCreateOutput(
            parentTaskId = args.parentTaskId,
            subTaskIds = subIds,
            planSource = planSource,
        )
        return Json.encodeToString(DecomposeAndCreateOutput.serializer(), out)
    }

    private fun parsePlan(text: String): List<String> {
        return runCatching {
            Json.decodeFromString<DecomposeTaskOutput>(text).subTasks
        }.getOrElse {
            text.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
        }
    }

    private fun extractText(response: Message.Assistant): String =
        response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()

    companion object {
        const val NAME = "decompose_and_create"
        const val DESCRIPTION =
            "Asks the LLM to break a task into sub-tasks, then creates each sub-task in one call. " +
                "Returns the created taskIds. planSource='llm' or 'empty' (LLM unavailable)."
    }
}
