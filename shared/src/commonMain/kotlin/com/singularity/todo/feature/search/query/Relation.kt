package com.singularity.todo.feature.search.query

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
