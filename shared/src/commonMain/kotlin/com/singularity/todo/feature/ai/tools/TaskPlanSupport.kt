package com.singularity.todo.feature.ai.tools

import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.logic.HealthRules
import com.singularity.todo.feature.tasks.domain.logic.TaskHealth
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionRequest
import com.singularity.todo.feature.tasks.domain.logic.TaskSelectionResolver
import com.singularity.todo.feature.tasks.domain.logic.stagnationWindowOf
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.comparisons.nullsLast
import kotlin.time.Clock

/**
 * What a planning tool is given about the user's tasks.
 *
 * Kept apart from [TaskToolSupport], which answers "what do these tasks look like" for
 * the read tools. A planner asks a narrower question — what changes the next choice —
 * and its projection is smaller on purpose: no descriptions, no checklists, no
 * pagination. Both ask the same app for membership ([TaskSelectionResolver]) and
 * health ([HealthRules]), so neither can answer differently from the screens.
 */
internal object TaskPlanSupport {
    /**
     * The tasks of [selection], named by their titles.
     *
     * For the tools that plan rather than list: asked for a named set, they work on
     * that set — the app's set, with the app's rules — instead of on a list of strings
     * the model made up.
     *
     * @param stalenessDays the stored staleness threshold the caller read, so that
     *   `stagnant` means the user's window here as it does everywhere else. A tool that
     *   omitted it would answer `stagnant` with the selector's 21-day default while
     *   `list_tasks` answered with the setting.
     */
    suspend fun select(
        selection: TaskSelection,
        taskRepository: TaskRepository,
        clock: Clock,
        zone: TimeZone,
        stalenessDays: Int,
    ): List<String> {
        val facts = facts(selection, taskRepository, clock, zone, stalenessDays)
        // Cap the set sent to the model; report the remainder as a count.
        return facts.tasks.map { it.title } +
            if (facts.total > facts.tasks.size) {
                listOf("…and ${facts.total - facts.tasks.size} more")
            } else {
                emptyList()
            }
    }

    /**
     * The facts a planner works from: the selected tasks, bounded, plus what was
     * completed today.
     *
     * The list is bounded because the context is finite; [PlanFacts.total] says what
     * was left out, so a truncated list is stated rather than implied.
     */
    suspend fun facts(
        selection: TaskSelection?,
        taskRepository: TaskRepository,
        clock: Clock,
        zone: TimeZone,
        stalenessDays: Int,
    ): PlanFacts {
        val all = taskRepository.observeByFilter(TaskSelectionResolver.baseFilter()).first()
        val context = TaskToolSupport.context(all, clock, zone)
        val window = stagnationWindowOf(stalenessDays) ?: TaskHealth.DEFAULT_STAGNATION_WINDOW
        val rules = HealthRules(window)

        val matched =
            selection?.let { selected ->
                all
                    .filter {
                        TaskSelectionResolver.matches(
                            request = TaskSelectionRequest(selection = selected),
                            task = it,
                            context = context,
                            stagnationWindow = window,
                        )
                    }.sortedWith(ORDER)
            } ?: emptyList()

        // A finished task is only a fact about today when its completion timestamp
        // falls on the user's local day; anything else would be an inference.
        val completedToday =
            all
                .filter { task ->
                    task.completedAt?.toLocalDateTime(zone)?.date == context.today &&
                        !task.isTrashed
                }.sortedWith(ORDER)

        return PlanFacts(
            today = context.today,
            tasks = matched.take(LIMIT).map { planTask(it, all, context, rules) },
            total = matched.size,
            completedToday = completedToday.map { planTask(it, all, context, rules) },
        )
    }

    /** The one task, projected for the planner. */
    private fun planTask(
        task: Task,
        allTasks: List<Task>,
        context: SelectorContext,
        rules: HealthRules,
    ): PlanTask =
        PlanTask(
            id = task.id.value,
            title = task.title,
            dueDate = task.dueDate?.toString(),
            dueTime = task.dueTime?.toString(),
            priority = task.priority.name,
            estimateMinutes = task.estimateMinutes,
            health = rules.bucketOf(task, context).name.lowercase(),
            blockedBy = Blocking.activeBlockers(task, allTasks).map { it.title },
            projectId = task.projectId?.value,
            completedAt = task.completedAt?.toString(),
        )

    /**
     * The order selected tasks come back in.
     *
     * Pinned first, then by due date, then by priority, then by id. The last one
     * matters: a page boundary needs an order with no ties, or two tasks with the
     * same due date could swap places between two calls and the reader would see
     * one of them twice.
     */
    val ORDER: Comparator<Task> =
        compareByDescending<Task> { it.isPinned }
            .thenBy(nullsLast()) { it.dueDate }
            .thenByDescending { it.priority.ordinal }
            .thenBy { it.id.value }

    /** How many tasks a planner is given at most. */
    private const val LIMIT = 50
}
