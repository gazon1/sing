package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionResolver
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** Which task to read. */
@Serializable
data class GetTaskInput(val taskId: String)

/**
 * One task, whole.
 *
 * The same shape [ListTasksTool] hands back when it is asked for full cards, so that
 * a task read from a list and the same task read on its own are the same thing.
 */
@Serializable
data class GetTaskOutput(
    val found: Boolean,
    val task: TaskDetail? = null,
    /** Set when there is no such task, so that a reader is not left guessing. */
    val reason: String? = null,
)

/**
 * Reads one task.
 *
 * ## What it answers
 *
 * "What is this task?" — everything about it: what it says, when it is due, what it is
 * waiting on, how far its checklist has got.
 */
class GetTaskTool(
    private val taskRepository: TaskRepository,
    private val checklistRepository: ChecklistRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val settingsRepository: SettingsRepository,
) : SimpleTool<GetTaskInput>(TypeToken.of(GetTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GetTaskInput): String {
        val all = taskRepository.observeByFilter(TaskSelectionResolver.baseFilter()).first()
        val task = all.firstOrNull { it.id.value == args.taskId }

        val output = if (task == null) {
            GetTaskOutput(found = false, reason = "There is no task with id \"${args.taskId}\".")
        } else {
            val context = TaskToolSupport.context(all, clock, timeZoneProvider.current())
            val stalenessDays = settingsRepository.stalenessThresholdDays.first()
            GetTaskOutput(
                found = true,
                task = TaskToolSupport.detail(task, all, context, stalenessDays, checklistRepository),
            )
        }

        return ToolOutputJson.encodeToString(GetTaskOutput.serializer(), output)
    }

    companion object {
        const val NAME = "get_task"

        /**
         * The description the model reads.
         *
         * It says what the card carries, so that the model asks for the tool when the
         * question needs a task in full rather than a list of titles.
         */
        const val DESCRIPTION = """
            Read one task in full, by its id.

            Returns the task's description, due date and time, priority, tags, whether it
            is pinned, how many subtasks it has, how far its checklist has got, and the
            tasks it is waiting on. Also says whether the task is overdue, blocked or
            stagnant.

            Use list_tasks to find the id first, and this to read the task the reader
            means.
        """
    }
}
