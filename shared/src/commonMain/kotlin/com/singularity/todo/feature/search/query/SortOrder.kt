package com.singularity.todo.feature.search.query

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
