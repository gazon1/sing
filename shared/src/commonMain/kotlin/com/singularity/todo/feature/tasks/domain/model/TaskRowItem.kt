package com.singularity.todo.feature.tasks.domain.model

/**
 * A single task row within a rendered section.
 *
 * Thin wrapper around [Task] that carries the display metadata a row needs.
 *
 * @param task The underlying task.
 * @param badge Optional per-task badge (e.g. "recurring", "overdue").
 * @param isBlocked true when the task has incomplete dependencies.
 * @param seriesDates How many dates the series of this row has; null when it stands alone.
 */
data class TaskRowItem(
    val task: Task,
    val badge: TaskBadge? = null,
    /** MR-1: true when the task has incomplete dependencies. */
    val isBlocked: Boolean = false,
    /** How many dates the series of this row has; null when it stands alone. */
    val seriesDates: Int? = null,
)
