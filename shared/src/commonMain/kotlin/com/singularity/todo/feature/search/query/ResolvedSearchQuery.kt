package com.singularity.todo.feature.search.query

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
 * - Free text for [com.singularity.todo.feature.notes.data.NotesRepository.search]
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
    /** Free-text string to pass to [com.singularity.todo.feature.notes.data.NotesRepository.search]. */
    val freeText: String? = null,
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
