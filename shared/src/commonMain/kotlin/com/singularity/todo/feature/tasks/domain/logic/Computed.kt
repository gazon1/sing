package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.LocalDate

/**
 * Derived predicates for [Task] — the single source of truth for computed state.
 *
 * These are **never** stored in Room; they are always computed on read.
 *
 * ## Rule (from `orgmode-functional-patterns.md` R2)
 *
 * Derived values must be computed through [com.singularity.todo.core.tree.cascadeUp] —
 * never as stored fields on entities.
 *
 * ## Usage
 *
 * ```kotlin
 * if (TaskComputed.isOverdue(task, today)) { ... }
 * if (TaskComputed.isReady(task))          { ... }
 * ```
 *
 * @see com.singularity.todo.core.tree.cascadeUp
 */
object TaskComputed {

    /**
     * A task is overdue when:
     * - it has a `dueDate`
     * - `dueDate` is strictly before `today`
     * - it is not completed
     * - it is not trashed (archived)
     *
     * Corresponds to `Selector.Overdue` and `DateBucket(RelativeBucket.Overdue)`.
     */
    fun isOverdue(task: Task, today: LocalDate): Boolean =
        task.dueDate != null &&
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
    fun isReady(task: Task): Boolean =
        !task.isCompleted && !task.isTrashed

    /**
     * A task is blocked when it has unsatisfied dependencies.
     *
     * ## Implementation (MR-1)
     *
     * Checks whether **any** task in [allTasks] that is referenced by [task]'s
     * `dependsOn` set is not yet completed. Returns `false` when the set is empty.
     *
     * The [allTasks] list must contain every task for the current user — it is
     * supplied by the ViewModel's `combine` so the predicate remains pure.
     *
     * @see com.singularity.todo.docs.decisions.2026-09-18-task-dependencies
     */
    fun isBlocked(task: Task, allTasks: List<Task>): Boolean {
        if (task.dependsOn.isEmpty()) return false
        val allTasksById = allTasks.associateBy { it.id }
        return task.dependsOn.any { depId ->
            val dep = allTasksById[depId]
            dep != null && !dep.isCompleted && !dep.isTrashed
        }
    }
}
