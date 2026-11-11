package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus

/**
 * A flat, UI-friendly representation of a search filter.
 *
 * Covers the most common filter use cases: task state, priority, tags,
 * project, due date, description presence, and pinned status.
 *
 * **Round-trip limitations (from Query → SimpleFilter → Query):**
 * - `Not(...)` conditions are dropped (UI has no negation)
 * - `Or` trees are not representable (would require a separate "match any" mode)
 * - `IsArchived` has no UI toggle in v1
 * - `Scheduled` date conditions are dropped (only `Due` is exposed in UI)
 * - Complex date relations (`GT`, `LT`, `NE`) are rounded to `Due.TODAY`
 *
 * @param states Explicitly selected task states to include.
 *               `null` means "no state filter" (all states shown in UI).
 *               Non-null set is converted to `HasStatus` conditions in Query.
 * @param priorities Which priorities to include (empty = no filter)
 * @param tagNames Tag names to filter by (combined with [matchAllTags])
 * @param matchAllTags When true, tasks must have ALL [tagNames].
 *                      When false, tasks with ANY of [tagNames] are included.
 * @param projectName Project name to filter by (null = no project filter)
 * @param due Due date bucket (TODAY, TOMORROW, THIS_WEEK, OVERDUE, NONE, CUSTOM)
 * @param customDueDate Start of custom date range when [due] is CUSTOM
 * @param customDueDateEnd End of custom date range when [due] is CUSTOM
 * @param hasDescription When true, only tasks with a non-blank description
 * @param pinned When true, only pinned tasks; null = no filter
 * @param sortOrder Sort criterion (default DUE)
 * @param sortDescending Sort direction
 * @param freeTextTerms The free-text terms of the query, one term each.
 *   A term is one `HasText` condition of the query it came from — a quoted
 *   phrase is one term however many words it holds, and two bare words are two
 *   terms. They are kept apart because the rule that matches them is a
 *   conjunction: joining them into one string would turn "milk bread" into the
 *   single term "milk bread", which no record carries.
 */
data class SimpleFilter(
    val states: Set<TaskStatus>? = null,
    val priorities: Set<TaskPriority> = emptySet(),
    val tagNames: Set<String> = emptySet(),
    val matchAllTags: Boolean = false,
    val projectName: String? = null,
    val due: DueCondition = DueCondition.NONE,
    val customDueDate: kotlinx.datetime.LocalDate? = null,
    val customDueDateEnd: kotlinx.datetime.LocalDate? = null,
    val hasDescription: Boolean? = null,
    val pinned: Boolean? = null,
    val sortOrder: SortOrder = SortOrder.DUE,
    val sortDescending: Boolean = false,
    val freeTextTerms: List<String> = emptyList(),
) {
    /** True when this filter has no active constraints. */
    val isEmpty: Boolean get() =
        states == null &&
            priorities.isEmpty() &&
            tagNames.isEmpty() &&
            projectName == null &&
            due == DueCondition.NONE &&
            hasDescription == null &&
            pinned == null &&
            freeTextTerms.isEmpty()

    /**
     * True when the filter narrows the search by something other than the free text.
     *
     * The free text is what the query says; a state, a priority, a tag, a project,
     * a due date, a description or a pin is a constraint the filter adds on top of
     * it. This is the question the no-results state asks before it offers to take
     * the constraints away: a query that matched nothing because of its words has
     * nothing to take away, and one that matched nothing because of its
     * constraints has.
     *
     * The sort is not a constraint: it orders the answer and does not narrow it.
     */
    val hasStructuredConstraints: Boolean get() =
        states != null ||
            priorities.isNotEmpty() ||
            tagNames.isNotEmpty() ||
            projectName != null ||
            due != DueCondition.NONE ||
            hasDescription != null ||
            pinned != null

    /**
     * This filter without its structured constraints.
     *
     * The free text and the sort stay: what the user asked for by words is not
     * what the filter sheet may take away, and the order of the answer is not
     * a narrowing of it either.
     */
    fun withoutStructuredConstraints(): SimpleFilter =
        copy(
            states = null,
            priorities = emptySet(),
            tagNames = emptySet(),
            matchAllTags = false,
            projectName = null,
            due = DueCondition.NONE,
            customDueDate = null,
            customDueDateEnd = null,
            hasDescription = null,
            pinned = null,
        )

    enum class DueCondition {
        TODAY,
        TOMORROW,
        THIS_WEEK,
        OVERDUE,
        NONE,

        /** Use [customDueDate] and [customDueDateEnd] for a custom range. */
        CUSTOM,
    }
}
