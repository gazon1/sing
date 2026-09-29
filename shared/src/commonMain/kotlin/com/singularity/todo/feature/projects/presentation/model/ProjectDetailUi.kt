package com.singularity.todo.feature.projects.presentation.model

import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
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
 * Combined read model for [com.singularity.todo.feature.projects.presentation.screen.ProjectDetailScreen].
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
    /**
     * Offset of the project's reminder, or null when none is set.
     *
     * Persisted as a [com.singularity.todo.feature.reminders.ProjectReminder] whose
     * `fireAt` is the project due date minus this offset. Held here as the *offset*
     * rather than the derived instant so the picker can show what the user chose even
     * after the due date moves.
     */
    val reminderOffsetMinutes: Int? = null,
) {
    val progressFraction: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
}
