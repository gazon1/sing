package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskBadge
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Pure badge computation for a single [Task].
 *
 * Returns the [TaskBadge] that should be shown for [task] on [today], or `null`
 * when the task has no special badge (normal in-date task).
 *
 * ## Priority (first non-null wins)
 *
 * | Priority | Badge | Condition |
 * |---|---|---|
 * | 1 | Blocked | [Blocking.isBlocked] — has incomplete dependencies |
 * | 2 | Pinned | [Task.isPinned] |
 * | 3 | Recurring | [Task.recurrence] is non-null |
 * | 4 | Completed | [Task.isCompleted] |
 * | 5 | Overdue | [task.dueDate] is before [today] and not completed |
 * | 6 | NoDate | [task.dueDate] is `null` |
 * | — | `null` | Normal in-date task |
 *
 * ## Single source of truth
 *
 * Both the agenda's evaluator and its selector badge rules delegate to this
 * function, so the priority table above is enforced in one place. It moved out
 * of the agenda feature because the plans timeline renders the same rows and
 * must reach the same verdict — one policy, one vocabulary.
 *
 * @param task The task to evaluate.
 * @param today The reference date for overdue / active checks.
 * @param allTasks Required only when [Blocking.isBlocked] needs to be evaluated.
 *                 Pass an empty list when badge evaluation for blocked tasks is not needed.
 */
fun computeTaskBadge(task: Task, today: LocalDate, allTasks: List<Task> = emptyList()): TaskBadge? {
    val blockedIds = Blocking.blockedTaskIds(allTasks)
    return computeTaskBadge(task, today, blockedIds)
}

/**
 * Badge for a task whose blocked-ness is already known.
 *
 * The batch form: [blockedIds] comes from one [Blocking.blockedTaskIds] call for
 * the whole list, so a caller rendering many rows does not rebuild the lookup
 * table once per row — which is what made the old per-task path quadratic.
 *
 * Semantically identical to the [allTasks] overload; the set is consulted instead
 * of recomputed.
 */
fun computeTaskBadge(task: Task, today: LocalDate, blockedIds: Set<TaskId>): TaskBadge? = when {
    task.id in blockedIds -> TaskBadge.Blocked
    task.isPinned -> TaskBadge.Pinned
    task.recurrence != null -> TaskBadge.Recurring
    task.isCompleted -> TaskBadge.Completed
    task.dueDate != null && task.dueDate < today -> TaskBadge.Overdue
    task.dueDate == null -> TaskBadge.NoDate
    else -> null
}
