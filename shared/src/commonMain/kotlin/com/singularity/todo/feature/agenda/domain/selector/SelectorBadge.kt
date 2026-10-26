package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.tasks.domain.model.TaskBadge
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.LocalDate

/**
 * Composes per-task [TaskBadge]s for a [Selector].
 *
 * Each transformer declares which [TaskBadge] to produce ([badgeFor]) when its
 * predicate is satisfied. Multiple transformers can be combined via [all] —
 * the first non-null badge wins.
 *
 * ## Usage
 *
 * ```kotlin
 * val transformers = DefaultBadgeRules.all(
 *     DefaultBadgeRules.blocked,
 *     DefaultBadgeRules.pinned,
 *     DefaultBadgeRules.recurring,
 *     DefaultBadgeRules.completed,
 *     DefaultBadgeRules.overdue,
 *     DefaultBadgeRules.noDate,
 * )
 * val badge = transformers.fold(null as TaskBadge?) { acc, tr ->
 *     acc ?: tr.badgeFor(task, tasks, today)
 * }
 * ```
 *
 * ## Coverage
 *
 * [DefaultBadgeRules] covers all 6 badge cases: Blocked, Pinned, Recurring,
 * Completed, Overdue, NoDate. Priority order is enforced by [SelectorTransformer.all].
 *
 * @see DefaultBadgeRules
 */
interface SelectorTransformer {
    /**
     * Returns the [TaskBadge] for [task], or null if [this] transformer
     * does not apply.
     *
     * @param task the task the badge is computed for.
     * @param allTasks every task in scope — a rule that looks at another task
     *   (whether a prerequisite is finished) cannot answer without it.
     * @param today the reference date for date-based rules.
     */
    fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge?

    companion object {
        /**
         * Combines multiple [SelectorTransformer]s into one.
         *
         * Evaluates transformers in order and returns the first non-null
         * [badgeFor] result.
         *
         * Evaluation order matters: put more-specific transformers first.
         */
        fun all(vararg transformers: SelectorTransformer): SelectorTransformer =
            CompositeTransformer(transformers.toList())

        private class CompositeTransformer(private val transformers: List<SelectorTransformer>) : SelectorTransformer {
            override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? {
                var result: TaskBadge? = null
                for (t in transformers) {
                    result = t.badgeFor(task, allTasks, today)
                    if (result != null) break
                }
                return result
            }
        }
    }
}

/**
 * Canonical badge rules that mirror [TaskBadgePolicy].
 *
 * Each rule is a [SelectorTransformer] that returns a non-null badge when
 * its predicate is satisfied. Composing them with [SelectorTransformer.all]
 * produces the same badge sequence as [TaskBadgePolicy].
 *
 * Priority order (enforced by [SelectorTransformer.all]):
 * Blocked → Pinned → Recurring → Completed → Overdue → NoDate.
 *
 * @see TaskBadgePolicy
 */
object DefaultBadgeRules {

    /**
     * Matches tasks that have unfinished prerequisites.
     *
     * The same predicate the rest of the app uses: a prerequisite that has
     * been finished, or discarded, or that does not exist any more, is not in
     * the way. A rule that answered "does this task have a dependency at all"
     * would report a task as blocked while its prerequisite sat finished in
     * the same list — two answers to one question.
     */
    val blocked: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? =
            if (Blocking.activeBlockers(task, allTasks).isNotEmpty()) TaskBadge.Blocked else null
    }

    /** Matches pinned tasks. */
    val pinned: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? =
            if (task.isPinned) TaskBadge.Pinned else null
    }

    /** Matches recurring tasks. */
    val recurring: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? =
            if (task.recurrence != null) TaskBadge.Recurring else null
    }

    /** Matches completed tasks. */
    val completed: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? =
            if (task.isCompleted) TaskBadge.Completed else null
    }

    /** Matches overdue tasks. */
    val overdue: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? =
            if (task.dueDate != null && task.dueDate < today && !task.isCompleted) {
                TaskBadge.Overdue
            } else {
                null
            }
    }

    /** Matches tasks with no due date. */
    val noDate: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, allTasks: List<Task>, today: LocalDate): TaskBadge? =
            if (task.dueDate == null) TaskBadge.NoDate else null
    }

    /**
     * Composes all six default rules into a single transformer.
     *
     * Order: Blocked → Pinned → Recurring → Completed → Overdue → NoDate.
     * This mirrors the priority of [TaskBadgePolicy].
     */
    val all: SelectorTransformer = SelectorTransformer.all(
        blocked,
        pinned,
        recurring,
        completed,
        overdue,
        noDate,
    )
}
