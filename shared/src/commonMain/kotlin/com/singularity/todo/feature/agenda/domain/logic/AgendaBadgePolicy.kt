package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.LocalDate

/**
 * Pure badge computation for a single [Task].
 *
 * Returns the [AgendaBadge] that should be shown for [task] on [today], or `null`
 * when the task has no special badge (normal in-date task).
 *
 * ## Priority (first non-null wins)
 *
 * | Priority | Badge | Condition |
 * |---|---|---|
 * | 1 | Blocked | [TaskComputed.isBlocked] — has incomplete dependencies |
 * | 2 | Pinned | [Task.isPinned] |
 * | 3 | Recurring | [Task.recurrence] is non-null |
 * | 4 | Completed | [Task.isCompleted] |
 * | 5 | Overdue | [task.dueDate] is before [today] and not completed |
 * | 6 | NoDate | [task.dueDate] is `null` |
 * | — | `null` | Normal in-date task |
 *
 * ## Single source of truth
 *
 * Both [AgendaEvaluator.computeBadge] and [com.singularity.todo.feature.agenda.domain.selector.DefaultBadgeRules]
 * delegate to this function so the priority table above is enforced in one place.
 *
 * @param task The task to evaluate.
 * @param today The reference date for overdue / active checks.
 * @param allTasks Required only when [TaskComputed.isBlocked] needs to be evaluated.
 *                 Pass an empty list when badge evaluation for blocked tasks is not needed.
 */
fun computeAgendaBadge(task: Task, today: LocalDate, allTasks: List<Task> = emptyList()): AgendaBadge? = when {
    TaskComputed.isBlocked(task, allTasks) -> AgendaBadge.Blocked
    task.isPinned -> AgendaBadge.Pinned
    task.recurrence != null -> AgendaBadge.Recurring
    task.isCompleted -> AgendaBadge.Completed
    task.dueDate != null && task.dueDate < today -> AgendaBadge.Overdue
    task.dueDate == null -> AgendaBadge.NoDate
    else -> null
}
