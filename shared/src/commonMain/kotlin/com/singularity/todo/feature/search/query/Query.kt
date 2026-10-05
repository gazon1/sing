package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — this type was part of a ported sealed
//   hierarchy; reimplemented as this project's own domain model against
//   docs/specs/search-query-grammar.md per option A1. See docs/legal/PROVENANCE.md

/**
 * Top-level parsed search query.
 *
 * Produced by [SingularityQueryParser] from a user-supplied string.
 * Wraps a [Condition] tree plus sort and execution options.
 *
 * @param condition The filter condition tree, or `null` for "match everything".
 * @param sortOrder How to order results within each category. Defaults to [SortOrder.DUE].
 * @param sortDescending When `true`, reverse the default sort direction.
 * @param options Execution limits ([Options.limit], [Options.offset]).
 */
data class Query(
    val condition: Condition? = null,
    val sortOrder: SortOrder = SortOrder.DUE,
    val sortDescending: Boolean = false,
    val options: Options = Options(),
) {
    companion object {
        /** An empty query — matches everything with default sort. */
        val EMPTY = Query()
    }

    /**
     * Returns `true` if this query has no filter conditions
     * and default options (i.e. equivalent to [EMPTY]).
     */
    val isEmpty: Boolean get() = condition == null && sortOrder == SortOrder.DUE && !sortDescending &&
        options == Options()
}
