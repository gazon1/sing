package com.singularity.todo.feature.tasks.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain-level task completion status.
 *
 * Replaces [com.singularity.todo.feature.tasks.presentation.model.TaskListFilter].
 * The three-way distinction (All / Active / Completed) is needed both for
 * the agenda DSL selector and for the UI filter chips.
 *
 * @see com.singularity.todo.feature.tasks.presentation.model.TaskListFilter (deleted, replaced by this type)
 */
@Serializable
enum class TaskStatus {
    /** No status filter — all non-trashed tasks. */
    All,

    /** Active (not completed) tasks. */
    Active,

    /** Completed tasks. */
    Completed,
}
