package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — this type was part of a ported sealed
//   hierarchy; reimplemented as this project's own domain model against
//   docs/specs/search-query-grammar.md per option A1. See docs/legal/PROVENANCE.md

/**
 * Query execution options.
 *
 * @param limit Maximum number of results per category (tasks / notes / projects / tags).
 *              Defaults to 50 to avoid unbounded result sets.
 * @param offset Number of result rows to skip (for pagination). Defaults to 0.
 */
data class Options(val limit: Int = 50, val offset: Int = 0)
