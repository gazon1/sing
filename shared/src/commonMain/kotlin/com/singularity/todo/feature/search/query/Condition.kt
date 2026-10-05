package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — this type was part of a ported sealed
//   hierarchy; reimplemented as this project's own domain model against
//   docs/specs/search-query-grammar.md per option A1. See docs/legal/PROVENANCE.md

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus

/**
 * Sealed hierarchy of all searchable conditions.
 *
 * Each leaf represents a primitive filter predicate.
 * [Not], [And], [Or] are logical combinators.
 *
 * Important: there is **no `not: Boolean` flag** on leaf conditions.
 * Negation is expressed exclusively through the [Not] wrapper.
 * This guarantees a single canonical representation for every query.
 */
sealed interface Condition {

    // ─── Primitive predicates ────────────────────────────────────────────────

    /** Full-text search across task title and description. */
    data class HasText(val text: String) : Condition

    /** Filters tasks by completion status. */
    data class HasStatus(val status: TaskStatus) : Condition

    /** Filters tasks by priority. */
    data class HasPriority(val priority: TaskPriority) : Condition

    /**
     * Filters tasks that have the given tag (by name — resolved to [TagId][com.singularity.todo.feature.tags.TagId] by [SearchQueryResolver]).
     *
     * Note: tag names are user-facing strings that must be resolved to IDs
     * via a name-lookup query against the database.
     */
    data class HasTag(val tagName: String) : Condition

    /**
     * Filters tasks that have **all** of the given tags (by name).
     * All tag names must be resolved to [TagId][com.singularity.todo.feature.tags.TagId] by [SearchQueryResolver].
     */
    data class HasAllTags(val tagNames: Set<String>) : Condition

    /**
     * Filters tasks belonging to the named project.
     * The project name is resolved to a [ProjectId][com.singularity.todo.feature.projects.domain.model.ProjectId]
     * by [SearchQueryResolver].
     */
    data class InProject(val name: String) : Condition

    /**
     * Filters tasks by their due date.
     *
     * The [interval] is a relative offset (e.g. `+3d` = in 3 days) and
     * [relation] determines whether the due date must be before, after, or exactly on
     * that date.
     *
     * Example: `Due(days=3, Relation.LE)` means "due within the next 3 days".
     */
    data class Due(val interval: QueryInterval, val relation: Relation) : Condition

    /**
     * Filters tasks by their scheduled start date.
     * Same semantics as [Due] but applied to the `scheduledDate` column.
     */
    data class Scheduled(val interval: QueryInterval, val relation: Relation) : Condition

    /** Filters tasks that have a non-empty description field. */
    data object HasDescription : Condition

    /** Filters pinned tasks. */
    data object IsPinned : Condition

    /** Filters archived tasks. */
    data object IsArchived : Condition

    // ─── Logical combinators ────────────────────────────────────────────────

    /** Logical negation of [inner]. */
    data class Not(val inner: Condition) : Condition

    /**
     * Logical conjunction of two or more conditions.
     * An empty [parts] list is treated as a no-op (matches everything).
     */
    data class And(val parts: List<Condition>) : Condition

    /**
     * Logical disjunction of two or more conditions.
     * An empty [parts] list is treated as a no-op (matches nothing).
     */
    data class Or(val parts: List<Condition>) : Condition
}
