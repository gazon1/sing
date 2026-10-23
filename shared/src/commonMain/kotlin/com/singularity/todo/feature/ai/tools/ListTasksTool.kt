package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.TaskPageCursor
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.core.ai.decodeTaskPageCursor
import com.singularity.todo.core.ai.encode
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionRequest
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionResolver
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlin.time.Clock
import kotlin.comparisons.nullsLast
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * What to read.
 *
 * @property selection which named set of tasks to read. The app's own names, so that
 *   "overdue" means what the Overdue section means.
 * @property projectId keep only the tasks in this project.
 * @property tagIds keep only the tasks carrying **every** one of these tags.
 * @property priorities keep only the tasks at one of these priorities.
 * @property detail how much of each task to hand back.
 * @property limit how many tasks to return at most.
 * @property cursor where to continue from, as handed out by a previous call.
 */
@Serializable
data class ListTasksInput(
    val selection: TaskSelection = TaskSelection.INBOX,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val priorities: List<String> = emptyList(),
    val detail: TaskDetailLevel = TaskDetailLevel.SUMMARY,
    val limit: Int = ListTasksTool.DEFAULT_LIMIT,
    val cursor: String? = null,
)

/**
 * The tasks that were read, and enough to know what was left out.
 *
 * [tasks] holds the summaries and [details] the full cards; which of the two is filled
 * depends on what was asked for, and the other is empty. [total] is how many tasks the
 * selection holds, so that a reader who sees a page of twenty knows whether there are
 * three tasks or three hundred.
 */
@Serializable
data class ListTasksOutput(
    val selection: String,
    val total: Int,
    val nextCursor: String? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val details: List<TaskDetail> = emptyList(),
    val health: TaskHealthSummary = TaskHealthSummary(),
    /** Set when the cursor handed in could not be used, and where the read started instead. */
    val cursorWarning: String? = null,
)

/**
 * Reads the user's tasks.
 *
 * ## What it answers
 *
 * "Which of my tasks are in this set, and what is wrong with them." The set is named
 * the way the app names it, and the rule that decides membership is the app's own rule
 * — see [TaskSelectionResolver] for why that matters.
 */
class ListTasksTool(
    private val taskRepository: TaskRepository,
    private val checklistRepository: ChecklistRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val tasksSettingsRepository: TasksSettingsRepository,
) : SimpleTool<ListTasksInput>(TypeToken.of(ListTasksInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: ListTasksInput): String {
        val request = TaskSelectionRequest(
            selection = args.selection,
            projectId = args.projectId,
            tagIds = args.tagIds,
            priorities = args.priorities,
        )

        val all = taskRepository.observeByFilter(TaskSelectionResolver.baseFilter()).first()
        val context = TaskToolSupport.context(all, clock, timeZoneProvider.current())
        val stalenessDays = tasksSettingsRepository.stalenessThresholdDays.first()

        val selected = all
            .filter { TaskSelectionResolver.matches(request, it, context) }
            .sortedWith(ORDER)

        val fingerprint = TaskSelectionResolver.fingerprint(request)
        val start = startOffset(args.cursor, fingerprint)

        val limit = args.limit.coerceIn(1, MAX_LIMIT)
        val page = selected.drop(start.offset).take(limit)

        val output = ListTasksOutput(
            selection = args.selection.name.lowercase(),
            total = selected.size,
            nextCursor = nextCursor(start.offset, page.size, selected.size, fingerprint),
            tasks = if (args.detail == TaskDetailLevel.SUMMARY) {
                page.map { TaskToolSupport.summary(it, all, context, stalenessDays) }
            } else {
                emptyList()
            },
            details = if (args.detail == TaskDetailLevel.FULL) {
                page.map { TaskToolSupport.detail(it, all, context, stalenessDays, checklistRepository) }
            } else {
                emptyList()
            },
            health = TaskToolSupport.health(selected, context, stalenessDays),
            cursorWarning = start.warning,
        )

        return ToolOutputJson.encodeToString(ListTasksOutput.serializer(), output)
    }

    /**
     * Where this page starts.
     *
     * A cursor that names a different selection is not a page of this list, and using
     * it as one would hand the reader a page of something else without saying so. So
     * the read starts from the beginning and says why.
     */
    private fun startOffset(cursor: String?, fingerprint: String): Start {
        if (cursor.isNullOrBlank()) return Start(0, null)

        val decoded = cursor.decodeTaskPageCursor()
            ?: return Start(0, "That cursor could not be read, so the list starts from the beginning.")

        if (decoded.fingerprint != fingerprint) {
            return Start(
                0,
                "That cursor was for a different selection, so the list starts from the beginning.",
            )
        }

        return Start(decoded.offset.coerceAtLeast(0), null)
    }

    private fun nextCursor(start: Int, pageSize: Int, total: Int, fingerprint: String): String? {
        val next = start + pageSize
        if (pageSize == 0 || next >= total) return null
        return TaskPageCursor(offset = next, fingerprint = fingerprint).encode()
    }

    /** Where a page starts, and what to say if that is not where the reader asked for. */
    private data class Start(val offset: Int, val warning: String?)

    companion object {
        const val NAME = "list_tasks"

        /**
         * The description the model reads.
         *
         * It names the sets it can ask for, because a model that does not know the
         * vocabulary will not use it — and it says the narrowing is an intersection,
         * which is the part a model gets wrong most often.
         */
        const val DESCRIPTION =
            "List the user's tasks. " +
                "The selection is one of: inbox, today, upcoming, overdue, blocked, stagnant, " +
                "pinned, someday, active, completed, all. " +
                "Inbox means open and not deferred; today means due today; upcoming means due " +
                "later; overdue means past its due date and still open; blocked means waiting on " +
                "something unfinished; stagnant means open, undated and untouched for a long time; " +
                "pinned means the user pinned it; someday means deferred; active means open; " +
                "completed means finished; all means everything that is not in the trash. " +
                "Narrowing is an intersection: projectId, tagIds and priorities each remove " +
                "tasks, so a request for two tags returns only the tasks carrying both. " +
                "Priorities are none, low, medium, high, urgent. " +
                "The result carries the number of tasks in the selection, how they are doing " +
                "(overdue, blocked, stagnant), and a cursor for the next page. Pass the cursor " +
                "back to continue; do not use a cursor from a different selection."

        /**
         * How many tasks a page holds unless the reader asks for fewer.
         *
         * Ten: a page is what a reader reads in one go, and a longer one costs context
         * for a question that rarely needs it. The cursor is how the rest arrives.
         */
        const val DEFAULT_LIMIT = 10

        /** The most a reader may ask for in one page. */
        const val MAX_LIMIT = 100

        /**
         * The order the tasks come back in.
         *
         * Pinned first, then by due date, then by priority, then by id. The last one
         * matters: a page boundary needs an order with no ties, or two tasks with the
         * same due date could swap places between two calls and the reader would see
         * one of them twice.
         */
        private val ORDER: Comparator<Task> = compareByDescending<Task> { it.isPinned }
            .thenBy(nullsLast()) { it.dueDate }
            .thenByDescending { it.priority.ordinal }
            .thenBy { it.id.value }
    }
}
