package com.singularity.todo.feature.search.query

// Provenance: REWRITTEN from Orgzly (GPL-3.0) — this type was part of a ported sealed
//   hierarchy; reimplemented as this project's own domain model against
//   docs/specs/search-query-grammar.md per option A1. See docs/legal/PROVENANCE.md

/**
 * Comparison relation for interval-based conditions.
 *
 * Used in [Condition.Due] and [Condition.Scheduled] to express
 * relative date constraints (e.g. "due within 3 days", "scheduled before today").
 */
enum class Relation {
    /** Equal to */
    EQ,

    /** Not equal to */
    NE,

    /** Less than */
    LT,

    /** Less than or equal */
    LE,

    /** Greater than */
    GT,

    /** Greater than or equal */
    GE,
}
