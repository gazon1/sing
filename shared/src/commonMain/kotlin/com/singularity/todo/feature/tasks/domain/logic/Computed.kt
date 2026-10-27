package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.LocalDate

/**
 * Derived predicates for [Task] — the single source of truth for computed state.
 *
 * These are **never** stored in Room; they are always computed on read.
 *
 * ## Rule (from `docs/decisions/2026-09-17-orgmode-functional-patterns.md` R2)
 *
 * Derived values must be computed through [com.singularity.todo.core.tree.cascadeUp] —
 * never as stored fields on entities.
 *
 * ## What is not here
 *
 * The blocking rule is not here: it has its own owner, [Blocking], because it
 * answers more than a yes/no — it has to say *which* prerequisites are in the
 * way, and the answer is used to ask the user a question.
 *
 * ## Usage
 *
 * ```kotlin
 * if (TaskComputed.isOverdue(task, today)) { ... }
 * if (TaskComputed.isReady(task))          { ... }
 * ```
 *
 * @see com.singularity.todo.core.tree.cascadeUp
 * @see Blocking
 */
object TaskComputed {

    /**
     * A task is overdue when:
     * - it has a `dueDate`
     * - `dueDate` is strictly before `today`
     * - it is not completed
     * - it is not trashed (archived)
     *
     * This is the one rule that answers "is this task overdue?": the agenda's
     * `Selector.Overdue` and `DateBucket(RelativeBucket.Overdue)`, the task
     * marking, the calendar mapper and the task filter all ask here.
     */
    fun isOverdue(task: Task, today: LocalDate): Boolean = task.dueDate != null &&
        task.dueDate < today &&
        !task.isCompleted &&
        !task.isTrashed

    /**
     * A task is ready when:
     * - it is not completed
     * - it is not trashed
     *
     * Used by agenda engines to filter out tasks that are "waiting on something".
     */
    fun isReady(task: Task): Boolean = !task.isCompleted && !task.isTrashed

    /**
     * A task is active on [today] when:
     * - it is not completed and not trashed
     * - it has a `startDate` that is on or before [today] (or has no startDate)
     * - it has an `endDate` that is on or after [today] (or has no endDate)
     *
     * If neither `startDate` nor `endDate` is set, the task is considered always active
     * (subject only to completion/trashed checks).
     *
     * Used by calendar and agenda views to determine whether a task is currently
     * within its active window.
     */
    fun isActive(task: Task, today: LocalDate): Boolean {
        if (task.isCompleted || task.isTrashed) return false
        val afterStart = task.startDate?.let { today >= it } ?: true
        val beforeEnd = task.endDate?.let { today <= it } ?: true
        return afterStart && beforeEnd
    }
}
