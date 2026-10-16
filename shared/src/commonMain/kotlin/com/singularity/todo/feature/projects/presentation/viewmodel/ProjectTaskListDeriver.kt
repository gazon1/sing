package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * The two independent list filters, as one value.
 *
 * Folded into one type rather than combined as a sixth flow: the `combine` that
 * consumes them is already at kotlinx's five-argument limit, and a vararg call
 * would collapse to `Array<Any?>`. Two toggles that always travel together are
 * one input, not two.
 */
internal data class TaskVisibility(val hideCompleted: Boolean = false, val hideBlocked: Boolean = false)

/**
 * Pure derivation of the task lists the project detail screen renders.
 *
 * Every function here is a total function of its arguments: no coroutines, no
 * repository, no clock. That is the whole point of extracting it. The logic used
 * to live inline in the ViewModel's collectors, which meant the only way to
 * assert a list filter was to construct a ViewModel with a scope and a set of
 * fakes — so the rules below were covered only indirectly, through state.
 *
 * Extracting them does not move any decision: every function reproduces what the
 * collector did, and the counting rules that were implicit in the combine are
 * now named.
 */
internal object ProjectTaskListDeriver {

    /**
     * How many tasks the detail list renders.
     *
     * The screen shows a preview; [com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi.totalCount]
     * carries the real number so the header can say "5 of 23". The cap predates
     * this class and is preserved exactly — it is a rendering decision, not a
     * derivation one, and changing it here would change the emitted state.
     */
    const val PREVIEW_LIMIT: Int = 5

    /**
     * Ids of tasks blocked by an unfinished dependency, resolved against the
     * **full** task list.
     *
     * Never computed against a filtered list. `TaskComputed.blockedIds` resolves
     * each `dependsOn` id through a map built from whatever list it is given; a
     * dependency a filter has hidden is simply absent from that map and reads as
     * "not blocking". So the function's contract is that it receives the complete
     * list, and it is the caller's job to honour it — which is why [visible]
     * takes the id set as an argument instead of recomputing it from whatever
     * subset it happens to be filtering.
     *
     * Note that with *these* two filters the hazard is not reachable — "hide
     * completed" only hides completed tasks, and a completed dependency does not
     * block. The contract still has to hold, because the same function serves
     * callers whose filters can hide an unfinished task (`AgendaEvaluator` passes
     * the complete list for exactly this reason).
     *
     * Only consulted when the filter is on, so the default view pays nothing.
     */
    fun blockedIds(allTasks: List<Task>): Set<TaskId> = TaskComputed.blockedIds(allTasks)

    /**
     * Tasks passing both filters, in repository order.
     *
     * [blocked] is passed in rather than computed here so a caller that has
     * already resolved it does not pay for a second pass.
     */
    fun visible(
        tasks: List<Task>,
        visibility: TaskVisibility,
        blocked: Set<TaskId>,
    ): List<Task> = tasks.filter { task ->
        (!visibility.hideCompleted || task.completedAt == null) &&
            (!visibility.hideBlocked || task.id !in blocked)
    }

    /**
     * The filtered list, capped at [PREVIEW_LIMIT].
     *
     * Kept separate from [visible] because the cap belongs to the rendered
     * preview while the counts are taken from the *unfiltered* list — the two
     * must not be derived from the same value or "5 of 23" becomes "5 of 5".
     */
    fun preview(
        tasks: List<Task>,
        visibility: TaskVisibility,
        blocked: Set<TaskId>,
    ): List<Task> = visible(tasks, visibility, blocked).take(PREVIEW_LIMIT)

    /**
     * Total number of tasks in the project, counted before filtering.
     */
    fun totalCount(tasks: List<Task>): Int = tasks.size

    /**
     * Number of completed tasks in the project, counted before filtering.
     *
     * Independent of `hideCompleted` on purpose: the count answers "how much of
     * this project is done", which stays true when the user hides the finished
     * rows.
     */
    fun completedCount(tasks: List<Task>): Int = tasks.count { it.completedAt != null }

    /**
     * All active tasks that are NOT in this project — the "add existing task"
     * quick-add picker.
     *
     * Excludes inbox tasks (`projectId == null`) and completed tasks. Sorted by
     * dueDate ascending (nulls last), then updatedAt descending, so the most
     * actionable candidates sit at the top of a list that is otherwise
     * arbitrarily ordered by the repository.
     */
    fun quickAddCandidates(
        all: List<Task>,
        projectId: ProjectId,
    ): List<Task> = all
        .filter { it.projectId != null && it.projectId != projectId && it.completedAt == null }
        .sortedWith(
            compareBy<Task, LocalDate?>(nullsLast()) { it.dueDate }
                .thenByDescending { it.updatedAt },
        )
}
