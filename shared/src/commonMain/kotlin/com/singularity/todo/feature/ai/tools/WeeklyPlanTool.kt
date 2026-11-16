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
import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.ai.prompts.Prompts
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Which half of the day the planner is answering for.
 *
 * The name is the wire value, so a model that has read the tool description can send
 * it. [MORNING] is the default, which is what every caller of `weekly_plan` meant
 * before the mode existed.
 */
@Serializable
enum class PlanMode {
    /** A grounded briefing of what to do today. */
    @SerialName("morning")
    MORNING,

    /** What the day actually produced, and what is still open. */
    @SerialName("evening")
    EVENING,
}

@Serializable
data class WeeklyPlanInput(
    /** The tasks to plan, when the reader names them. */
    val tasks: List<String> = emptyList(),
    /** The app's own set of tasks to plan, when the reader names one instead. */
    val selection: TaskSelection? = null,
    val mode: PlanMode = PlanMode.MORNING,
)

/** One named part of a briefing. */
@Serializable
data class PlanSection(val title: String, val items: List<String> = emptyList())

/**
 * The plan, in sections.
 *
 * [items] is the focus set and keeps the shape the tool has always returned: the tasks
 * the day is planned around, in order. [sections] explains the facts behind it, and
 * [firstAction] is the one physical step that starts the top task.
 */
@Serializable
data class WeeklyPlanOutput(
    val items: List<String> = emptyList(),
    val sections: List<PlanSection> = emptyList(),
    val firstAction: String? = null,
)

/**
 * Plans the user's day — a morning briefing or an evening review.
 *
 * ## What changed, and what did not
 *
 * The tool used to hand the model a list of titles. A title cannot say that a task is
 * overdue, blocked, due at 17:00 or estimated at ninety minutes, so the plan was
 * grounded in nothing. It now receives a small projection of each task — id, title, due
 * date and time, priority, estimate, health, blockers and project — built from the same
 * selector and the same [HealthRules][com.singularity.todo.feature.tasks.domain.logic.HealthRules]
 * every other surface uses. The tool name is unchanged: `weekly_plan` is the name the
 * MCP surface exports, and the prompt has always been about the next day.
 *
 * ## What it will not do
 *
 * It ranks and explains; it does not reclassify. A task the app calls overdue is
 * described as overdue, and a task with no estimate is unknown rather than small. The
 * evening review reads `completedAt` and nothing else: an unsaved morning plan is not
 * reconstructed, and no task is changed — a carry-over is advice, not a write.
 */
class WeeklyPlanTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val tasksSettingsRepository: TasksSettingsRepository,
) : SimpleTool<WeeklyPlanInput>(TypeToken.of(WeeklyPlanInput::class.java), NAME, DESCRIPTION) {
    private val logger = Logger.withTag("WeeklyPlan")

    override suspend fun execute(args: WeeklyPlanInput): String {
        val zone = timeZoneProvider.current()
        val evening = args.mode == PlanMode.EVENING
        // An evening review without a named set is still a review of the open day:
        // asking for nothing but completions would leave the overdue, blocked and
        // still-relevant tasks out of the answer that is supposed to name them.
        val selection = args.selection ?: if (evening) TaskSelection.ACTIVE_NOT_DEFERRED else null
        val facts =
            TaskPlanSupport.facts(
                selection = selection,
                taskRepository = taskRepository,
                clock = clock,
                zone = zone,
                stalenessDays = tasksSettingsRepository.stalenessThresholdDays.first(),
            )
        val p =
            prompt(Prompt.Empty, KoogClock.System) {
                system(if (evening) Prompts.eveningReviewSystem else Prompts.weeklyPlanSystem)
                user(
                    Prompts.weeklyPlanUser(
                        mode = args.mode.name.lowercase(),
                        today = facts.today.toString(),
                        timeZone = zone.id,
                        tasks = planJson(facts.tasks),
                        omitted = facts.total - facts.tasks.size,
                        completedToday = if (evening) planJson(facts.completedToday) else null,
                        completedTodayOmitted =
                            if (evening) facts.completedTodayTotal - facts.completedToday.size else 0,
                        namedTasks = args.tasks,
                    ),
                )
            }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        val output = parsePlan(text)
        return Json.encodeToString(WeeklyPlanOutput.serializer(), output)
    }

    /**
     * Reads the model's reply.
     *
     * A reply that is a bare list of titles is what the old prompt produced, and a
     * caller may still get one from a model that ignored the schema; it becomes the
     * focus set rather than an error, because a planner that fails on a readable
     * answer is worse than one that says less.
     */
    private fun parsePlan(text: String): WeeklyPlanOutput =
        runCatchingCancellable { MODEL_JSON.decodeFromString(WeeklyPlanOutput.serializer(), text) }
            .getOrElse { failure ->
                logger.w(failure) { "unreadable plan" }
                WeeklyPlanOutput(
                    items =
                        runCatchingCancellable { MODEL_JSON.decodeFromString<List<String>>(text) }
                            .getOrDefault(emptyList()),
                )
            }

    private fun planJson(tasks: List<PlanTask>): String =
        Json.encodeToString(ListSerializer(PlanTask.serializer()), tasks)

    companion object {
        const val NAME = "weekly_plan"
        const val DESCRIPTION =
            "Plan the user's day from their real tasks. mode=morning (the default) gives a " +
                "briefing: what needs attention, what is due today, what is coming up, a " +
                "3-5 task focus set that fits the day, and the first physical action. " +
                "mode=evening reviews the day instead: what was completed today, what is " +
                "still open, overdue or blocked, and what needs a decision. Without a " +
                "selection, an evening review reads every unfinished task that is not deferred. " +
                "Each task comes with its due date and time, priority, estimate, health " +
                "(fine, overdue, blocked or stagnant), blockers and project. Health is " +
                "classified by the app, not by you: do not redefine it, and do not invent " +
                "a due date, blocker, estimate or project that the facts do not contain. " +
                "A missing estimate is unknown, not zero. Carry-over is a suggestion; " +
                "nothing is changed."

        private val MODEL_JSON = Json { ignoreUnknownKeys = true }

        private fun extractText(response: Message.Assistant): String =
            response.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
    }
}
