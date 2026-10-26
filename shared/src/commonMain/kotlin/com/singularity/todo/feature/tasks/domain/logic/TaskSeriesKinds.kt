package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * The kind of a series, decided by where its dates come from.
 *
 * A **picked-dates** series has dates the user chose and no rule. A **recurring**
 * series has the rule's own occurrences as its dates and the rule on every member.
 * The two never mix (REQ-TS-009): a member of a picked-dates series carries no
 * rule, and a member of a recurring series carries the rule.
 *
 * Separated from [TaskSeriesInvariants] because it is about what kind of series a
 * batch is, not about the fields its members must agree on — a different question,
 * and one that keeps the fine-grained rules file about one thing.
 */
object TaskSeriesKinds {

    /** The two kinds a series can be. */
    enum class Kind { PickedDates, Recurring }

    /**
     * The kind of the series [members] form, or `null` when they do not form one.
     *
     * The rule is the discriminator, and it must agree across the members: a batch
     * whose members disagree about the rule is not one series at all, so the answer
     * is `null` rather than either kind.
     */
    fun of(members: List<Task>): Kind? {
        if (members.isEmpty()) return null
        val rules = members.map { it.recurrence }.distinct()
        if (rules.size != 1) return null
        return if (rules.single() == null) Kind.PickedDates else Kind.Recurring
    }

    /**
     * The reason two series may not be joined, or `null` when they may.
     *
     * A join takes the target's id and shared fields onto the other series' rows, so
     * it may not mix the kinds: a recurring series with a series of picked dates
     * would leave the result holding a rule and hand-picked dates, and two recurring
     * series with different rules would leave the members disagreeing about the
     * work. The refusal names the reason (FR-050).
     */
    fun joinRefusal(target: List<Task>, other: List<Task>): String? {
        val targetKind = of(target) ?: return "The series being joined into is not one series"
        val otherKind = of(other) ?: return "The series being joined is not one series"
        if (targetKind != otherKind) {
            return "A recurring series and a series of picked dates cannot be joined"
        }
        val rulesDiffer = targetKind == Kind.Recurring && target.first().recurrence != other.first().recurrence
        return if (rulesDiffer) "Two recurring series with different rules cannot be joined" else null
    }
}
