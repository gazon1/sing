package com.singularity.todo.feature.search.query

/**
 * Query execution options.
 *
 * @param limit Maximum number of results per category (tasks / notes / projects / tags).
 *              Defaults to 50 to avoid unbounded result sets.
 * @param offset Number of result rows to skip (for pagination). Defaults to 0.
 */
data class Options(
    val limit: Int = 50,
    val offset: Int = 0,
)
