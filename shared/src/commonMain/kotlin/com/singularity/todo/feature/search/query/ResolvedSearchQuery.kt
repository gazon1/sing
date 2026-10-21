package com.singularity.todo.feature.search.query

import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import kotlinx.datetime.LocalDate

/**
 * Result of resolving a [Query] against the database.
 *
 * Contains everything needed to execute the search:
 * - A [TaskFilter] for the task DAO (nullable if query has no task-relevant conditions)
 * - Explicit date range (when [TaskFilter.ByDateRange] is used)
 * - Tag and project IDs resolved from names
 * - Unknown names that need post-filtering
 * - The free text of the query, joined into one string
 * - The free-text rule ([freeTextRule]) every kind of record is matched against
 * - Sort and pagination options
 * - A post-filter predicate for conditions that can't be expressed in SQL
 */
data class ResolvedSearchQuery(
    /** Task DAO filter, or null if the query has no task-relevant conditions. */
    val taskFilter: TaskFilter?,
    /** Explicit date range when [taskFilter] is [TaskFilter.ByDateRange]. */
    val dateRange: DateRange?,
    /** IDs of tags resolved from tag names, for post-filtering. */
    val resolvedTagIds: Set<String> = emptySet(),
    /** Names of tags that were not found in the database. */
    val unknownTagNames: Set<String> = emptySet(),
    /** ID of the project resolved from its name, for post-filtering. */
    val resolvedProjectId: String? = null,
    /** Names of projects that were not found in the database. */
    val unknownProjectNames: Set<String> = emptySet(),
    val sortOrder: SortOrder = SortOrder.DUE,
    val sortDescending: Boolean = false,
    val options: Options = Options(),
    /**
     * The free text of the query, joined into one string.
     *
     * This is the human-readable form of the same terms [freeTextRule] holds. It is
     * not what records are matched against — the rule is — and it is not a phrase
     * that has to occur as a whole.
     */
    val freeText: String? = null,
    /**
     * The narrowing the full-text index can do for this query — or `null` when
     * the index cannot narrow it.
     *
     * The index answers positive questions only: it finds the records that carry
     * a term, and it has no way of expressing "must not carry". A rule made only
     * of negated terms therefore has no index narrowing, and a rule that
     * disjoins a negated term with a positive one has none either — the
     * records that match by *not* carrying the negated term are exactly the
     * ones the index would throw away.
     *
     * When this is `null` the search reads the rows and matches them in memory.
     */
    val textMatch: TextMatchQuery? = null,
    /**
     * The free-text rule of this query — the terms, their polarity, and whether they
     * are alternatives.
     *
     * The one rule every kind of record is matched against. The resolver builds it;
     * the use case applies it to notes, projects and tags, and the task post-filter
     * applies it to tasks. See [FreeTextRule].
     */
    val freeTextRule: FreeTextRule = FreeTextRule(),
    /**
     * True if any condition requires in-memory post-filtering
     * (e.g. negated conditions, OR trees, pinned, archived, has:description).
     */
    val needsPostFilter: Boolean = false,
    /**
     * In-memory post-filter for [Task] sequences.
     * Applied after the DAO flow is collected.
     * Returns the input sequence filtered according to unresolved conditions.
     */
    val postFilter: (Sequence<Task>) -> Sequence<Task> = { it },
    /**
     * True if the top-level condition is an OR (or the only condition is OR).
     * When true, [postFilter] combines predicates with ANY (OR) semantics.
     * When false (default), [postFilter] combines predicates with ALL (AND) semantics.
     */
    val isOrPostFilter: Boolean = false,
) {
    data class DateRange(val from: LocalDate, val to: LocalDate)
}
