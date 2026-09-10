package com.singularity.todo.feature.projects

import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * DTO for a single option in the parent-project picker.
 * Excludes [Project] itself to avoid cycles and omits deleted / non-root items.
 */
data class ParentOption(
    val id: ProjectId,
    val name: String,
    /** True if this option is currently the parent of the displayed project. */
    val isCurrent: Boolean,
)

/**
 * Combined read model for [ProjectDetailScreen].
 * Aggregates the project with its task list and aggregate counts.
 */
data class ProjectDetailUi(
    val project: Project,
    /** Tasks to show inline (up to 5). Full list on [TasksByProjectScreen]. */
    val tasks: List<Task>,
    val totalCount: Int,
    val completedCount: Int,
    /** Child projects under this project. */
    val childProjects: List<Project>,
    /** The parent project, or null if this is a root project. */
    val parent: Project?,
) {
    val progressFraction: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
}
