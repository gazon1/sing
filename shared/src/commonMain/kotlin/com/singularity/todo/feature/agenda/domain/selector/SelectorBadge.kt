package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.LocalDate

/**
 * Composes per-task [AgendaBadge]s for a [Selector].
 *
 * Each transformer declares which [AgendaBadge] to produce ([badgeFor]) when its
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
 * val badge = transformers.fold(null as AgendaBadge?) { acc, tr ->
 *     acc ?: tr.badgeFor(task, today)
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
     * Returns the [AgendaBadge] for [task], or null if [this] transformer
     * does not apply.
     */
    fun badgeFor(task: Task, today: LocalDate): AgendaBadge?

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
            override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? {
                var result: AgendaBadge? = null
                for (t in transformers) {
                    result = t.badgeFor(task, today)
                    if (result != null) break
                }
                return result
            }
        }
    }
}

/**
 * Canonical badge rules that mirror [AgendaBadgePolicy].
 *
 * Each rule is a [SelectorTransformer] that returns a non-null badge when
 * its predicate is satisfied. Composing them with [SelectorTransformer.all]
 * produces the same badge sequence as [AgendaBadgePolicy].
 *
 * Priority order (enforced by [SelectorTransformer.all]):
 * Blocked → Pinned → Recurring → Completed → Overdue → NoDate.
 *
 * @see AgendaBadgePolicy
 */
object DefaultBadgeRules {

    /** Matches tasks that have incomplete dependencies (blocked). */
    val blocked: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.dependsOn.isNotEmpty()) AgendaBadge.Blocked else null
    }

    /** Matches pinned tasks. */
    val pinned: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.isPinned) AgendaBadge.Pinned else null
    }

    /** Matches recurring tasks. */
    val recurring: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.recurrence != null) AgendaBadge.Recurring else null
    }

    /** Matches completed tasks. */
    val completed: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.isCompleted) AgendaBadge.Completed else null
    }

    /** Matches overdue tasks. */
    val overdue: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.dueDate != null && task.dueDate < today && !task.isCompleted) {
                AgendaBadge.Overdue
            } else {
                null
            }
    }

    /** Matches tasks with no due date. */
    val noDate: SelectorTransformer = object : SelectorTransformer {
        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.dueDate == null) AgendaBadge.NoDate else null
    }

    /**
     * Composes all six default rules into a single transformer.
     *
     * Order: Blocked → Pinned → Recurring → Completed → Overdue → NoDate.
     * This mirrors the priority of [AgendaBadgePolicy].
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
