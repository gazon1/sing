package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — this type was part of a ported sealed
//   hierarchy; reimplemented as this project's own domain model against
//   docs/specs/search-query-grammar.md per option A1. See docs/legal/PROVENANCE.md

/**
 * Sort order for search results within each result category.
 *
 * Each value corresponds to an `ORDER BY` column in the underlying DAO query.
 */
enum class SortOrder {
    /** Sort by due date. */
    DUE,

    /** Sort by title (alphabetical). */
    TITLE,

    /** Sort by creation timestamp. */
    CREATED,

    /** Sort by last update timestamp. */
    UPDATED,

    /** Sort by priority (highest first). */
    PRIORITY,
}
