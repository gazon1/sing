package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.datetime.LocalDate

/**
 * Composes per-task [AgendaBadge]s for a [Selector].
 *
 * Each transformer declares which tasks it applies to ([matches]) and what
 * [AgendaBadge] to produce ([badgeFor]). Multiple transformers can be combined
 * via [all] — the first non-null badge wins.
 *
 * ## Usage
 *
 * ```kotlin
 * val transformers = DefaultBadgeRules.all(
 *     DefaultBadgeRules.pinned,
 *     DefaultBadgeRules.completed,
 * )
 * val badge = transformers.fold(null as AgendaBadge?) { acc, tr ->
 *     acc ?: tr.badgeFor(task)
 * }
 * ```
 *
 * ## Coverage
 *
 * [DefaultBadgeRules] covers all 4 legacy badge cases: Pinned, Completed,
 * NoDate, Overdue.
 *
 * @see DefaultBadgeRules
 */
interface SelectorTransformer {
    /**
     * Returns true if [this] transformer applies to [task] on [today].
     *
     * Used to determine scope. A transformer with broader scope should be
     * listed before a narrower one in [all] composition.
     */
    fun Selector.matches(task: Task, today: LocalDate): Boolean

    /**
     * Returns the [AgendaBadge] for [task], or null if [this] transformer
     * does not apply.
     *
     * Called only when [matches] returns true.
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

        private class CompositeTransformer(
            private val transformers: List<SelectorTransformer>,
        ) : SelectorTransformer {
            override fun Selector.matches(task: Task, today: LocalDate): Boolean = true

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
 * Canonical badge rules that mirror the legacy [computeBadge] logic.
 *
 * Each rule is a [SelectorTransformer] that returns a non-null badge when
 * its predicate is satisfied. Composing them with [SelectorTransformer.all]
 * produces the same badge sequence as the original `when` expression.
 */
object DefaultBadgeRules {

    /** Matches pinned tasks. */
    val pinned: SelectorTransformer = object : SelectorTransformer {
        override fun Selector.matches(task: Task, today: LocalDate): Boolean = true

        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.isPinned) AgendaBadge.Pinned else null
    }

    /** Matches completed tasks. */
    val completed: SelectorTransformer = object : SelectorTransformer {
        override fun Selector.matches(task: Task, today: LocalDate): Boolean = true

        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.isCompleted) AgendaBadge.Completed else null
    }

    /** Matches tasks with no due date. */
    val noDate: SelectorTransformer = object : SelectorTransformer {
        override fun Selector.matches(task: Task, today: LocalDate): Boolean = true

        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.dueDate == null) AgendaBadge.NoDate else null
    }

    /** Matches overdue tasks. */
    val overdue: SelectorTransformer = object : SelectorTransformer {
        override fun Selector.matches(task: Task, today: LocalDate): Boolean = true

        override fun badgeFor(task: Task, today: LocalDate): AgendaBadge? =
            if (task.dueDate != null && task.dueDate < today && !task.isCompleted) {
                AgendaBadge.Overdue
            } else null
    }

    /**
     * Composes all four default rules into a single transformer.
     *
     * Order: Pinned → Completed → NoDate → Overdue.
     * This mirrors the priority of the original `computeBadge` `when` expression.
     */
    val all: SelectorTransformer = SelectorTransformer.all(pinned, completed, noDate, overdue)
}
