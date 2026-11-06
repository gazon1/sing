package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.serialization.WireNamed
import kotlinx.serialization.Serializable

/**
 * Domain-level task completion status.
 *
 * Replaces [com.singularity.todo.feature.tasks.presentation.model.TaskListFilter].
 * The three-way distinction (All / Active / Completed) is needed both for
 * the agenda DSL selector and for the UI filter chips.
 */
@Serializable
enum class TaskStatus(override val wireName: String) : WireNamed {
    /** No status filter — all non-trashed tasks. */
    All("all"),

    /** Active (not completed) tasks. */
    Active("active"),

    /** Completed tasks. */
    Completed("completed"),
}
