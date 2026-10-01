package com.singularity.todo.feature.tasks.domain.model

/**
 * Semantic verb for a typed task dependency link.
 *
 * Each value represents the relationship from the perspective of the **owner** task
 * (the task that holds the dependency edge).
 *
 * @see TaskDependency
 * @see com.singularity.todo.docs.decisions.2026-10-01-typed-task-dependency-links
 */
enum class DependencyVerb {
    /** Default. The owner task is blocked by the dependency. */
    BLOCKS,

    /** The owner task follows up on the dependency. */
    FOLLOWS_UP,

    /** The owner task duplicates the dependency. */
    DUPLICATES,

    /** The owner task fixes the dependency. */
    FIXES,

    /** The owner task supersedes the dependency. */
    SUPERSEDES,
}
