package com.singularity.todo.feature.ai.tools

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.logic.TaskCognitiveLoad
import com.singularity.todo.feature.tasks.domain.logic.TaskCognitiveLoadCalculator
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthBucket
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionRequest
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionResolver
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The parts of reading a task that two tools share.
 *
 * `list_tasks` and `get_task` answer different questions about the same thing, and the
 * answers have to agree: a task the list calls overdue and the card calls fine is a
 * task the reader cannot reason about. So the mapping from a task to what a reader sees
 * lives here, once.
 */
internal object TaskToolSupport {

    /**
     * The reference date and the blocked set the rules are evaluated against.
     *
     * The blocked set is computed once for the whole list, because computing it per
     * task would make classifying a list quadratic — the same reason the statistics
     * screen computes it once.
     */
    fun context(tasks: List<Task>, clock: Clock, zone: TimeZone): SelectorContext = SelectorContext(
        today = clock.now().toLocalDateTime(zone).date,
        now = clock.now(),
        blockedIds = Blocking.blockedTaskIds(tasks),
        // A task completed an hour ago may still carry an old `updatedAt`, so the
        // finished moment counts as the last movement. The statistics screen uses the
        // same rule, and a reader told "stagnant" by one screen and "fine" by the
        // other has been told two things.
        lastActivityOf = { task -> maxOf(task.updatedAt, task.completedAt ?: task.updatedAt) },
    )

    /**
     * The tasks of [selection], named by their titles.
     *
     * For the tools that plan rather than list: asked for a named set, they work on
     * that set — the app's set, with the app's rules — instead of on a list of strings
     * the model made up.
     */
    suspend fun select(
        selection: TaskSelection,
        taskRepository: TaskRepository,
        clock: Clock,
        zone: TimeZone,
    ): List<String> {
        val all = taskRepository.observeByFilter(TaskSelectionResolver.baseFilter()).first()
        val context = context(all, clock, zone)
        return all
            .filter { TaskSelectionResolver.matches(TaskSelectionRequest(selection = selection), it, context) }
            .map { it.title }
    }

    /** The short form of [task]. */
    fun summary(
        task: Task,
        allTasks: List<Task>,
        context: SelectorContext,
        stalenessDays: Int,
    ): TaskSummary {
        val load = cognitiveLoad(task, context, stepCount = subtaskCount(task, allTasks))
        return TaskSummary(
            id = task.id.value,
            title = task.title,
            projectId = task.projectId?.value,
            dueDate = task.dueDate?.toString(),
            priority = task.priority.name,
            tags = task.tags.map { it.value },
            status = status(task),
            health = bucket(task, context, stalenessDays).name.lowercase(),
            load = load.load,
            toxic = load.isToxic,
            estimateMinutes = task.estimateMinutes,
            isPinned = task.isPinned,
        )
    }

    /** The whole of [task]. */
    suspend fun detail(
        task: Task,
        allTasks: List<Task>,
        context: SelectorContext,
        stalenessDays: Int,
        checklistRepository: ChecklistRepository,
    ): TaskDetail {
        val checklist = checklistRepository.watchByTask(task.id.value).firstOrNull().orEmpty()
        val blockers = Blocking.activeBlockers(task, allTasks)
            .map { it.title }

        val steps = subtaskCount(task, allTasks) + checklist.size
        val load = cognitiveLoad(task, context, stepCount = steps)

        return TaskDetail(
            id = task.id.value,
            title = task.title,
            description = task.description,
            projectId = task.projectId?.value,
            dueDate = task.dueDate?.toString(),
            dueTime = task.dueTime?.toString(),
            priority = task.priority.name,
            tags = task.tags.map { it.value },
            status = status(task),
            health = bucket(task, context, stalenessDays).name.lowercase(),
            load = load.load,
            stress = load.stress,
            value = load.value,
            toxic = load.isToxic,
            estimateMinutes = task.estimateMinutes,
            isPinned = task.isPinned,
            subtaskCount = subtaskCount(task, allTasks),
            checklistDone = checklist.count { it.isCompleted },
            checklistTotal = checklist.size,
            blockers = blockers,
            createdAt = task.createdAt.toString(),
            updatedAt = task.updatedAt.toString(),
        )
    }

    /**
     * How the tasks in the selection are doing.
     *
     * The counts come from the app's own health classification, so they are the counts
     * the statistics screen would show for the same tasks.
     */
    fun health(tasks: List<Task>, context: SelectorContext, stalenessDays: Int): TaskHealthSummary {
        val open = tasks.filter { !it.isCompleted && !it.isTrashed }
        val perTask = open.map { task -> bucket(task, context, stalenessDays) }
        val loads = open.map { task -> TaskCognitiveLoadCalculator.loadOf(task.estimateMinutes, 0) }
        return TaskHealthSummary(
            totalOpen = open.size,
            overdue = perTask.count { it == TaskHealthBucket.OVERDUE },
            blocked = perTask.count { it == TaskHealthBucket.BLOCKED },
            stagnant = perTask.count { it == TaskHealthBucket.STAGNANT },
            toxic = open.count { task -> isToxic(task, context) },
            averageLoad = if (loads.isEmpty()) 0 else loads.sum() / loads.size,
        )
    }

    /** `active` or `completed`. */
    private fun status(task: Task): String = if (task.isCompleted) "completed" else "active"

    /**
     * The health bucket of [task], named the way the app names it.
     *
     * The precedence is the app's: overdue first, then blocked, then stagnant. A task
     * that is both overdue and blocked is reported as overdue, because that is the one
     * the user can act on today — and the card still says what it is waiting on.
     */
    private fun bucket(task: Task, context: SelectorContext, stalenessDays: Int): TaskHealthBucket {
        if (task.isCompleted) return TaskHealthBucket.FINE

        val overdue = Selector.DateBucket(RelativeBucket.Overdue)
        val blocked = Selector.Blocked
        val stagnant = Selector.Stagnant(stalenessDays.days)

        return when {
            overdue.matches(task, context) -> TaskHealthBucket.OVERDUE
            blocked.matches(task, context) -> TaskHealthBucket.BLOCKED
            stagnant.matches(task, context) -> TaskHealthBucket.STAGNANT
            else -> TaskHealthBucket.FINE
        }
    }

    /** How many steps the task is broken into, by subtask. */
    private fun subtaskCount(task: Task, allTasks: List<Task>): Int = allTasks.count { it.parentTaskId == task.id }

    /** How much the task weighs on the user. */
    private fun cognitiveLoad(
        task: Task,
        context: SelectorContext,
        stepCount: Int,
    ): TaskCognitiveLoad = TaskCognitiveLoadCalculator.of(
        estimateMinutes = task.estimateMinutes,
        stepCount = stepCount,
        blocked = task.id in context.blockedIds,
        staleDays = staleDaysOf(task, context),
        priority = task.priority,
    )

    /** Whether the task is a burden rather than a big task. */
    private fun isToxic(task: Task, context: SelectorContext): Boolean =
        cognitiveLoad(task, context, stepCount = 0).isToxic

    /** How many days the task has sat without movement. */
    private fun staleDaysOf(task: Task, context: SelectorContext): Long {
        val elapsed = context.now - context.lastActivityOf(task)
        return elapsed.inWholeDays.coerceAtLeast(0)
    }
}
