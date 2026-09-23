package com.singularity.todo.feature.search.query

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
    val isEmpty: Boolean get() = condition == null && sortOrder == SortOrder.DUE && !sortDescending && options == Options()
}
