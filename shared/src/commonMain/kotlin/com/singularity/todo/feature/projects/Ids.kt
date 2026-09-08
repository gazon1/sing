package com.singularity.todo.feature.projects

import java.util.UUID
import kotlin.time.Instant

@JvmInline
value class ProjectId(val value: String) {
    companion object {
        fun generate() = ProjectId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = ProjectId(value)
    }
}

data class Project(
    val id: ProjectId,
    val name: String,
    val color: Int, // ARGB
    val icon: String? = null,
    val description: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isDefault: Boolean = false,
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val team: String? = null,
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null,
    val parentId: ProjectId? = null,
    val sortOrder: Int = 0,
    val idempotencyKey: String? = null,
    val externalId: String? = null,
    val userId: String
)

/** Domain projection of [Project] with task counts, used by [ProjectsViewModel] UI state. */
data class ProjectWithCounts(
    val project: Project,
    val totalCount: Int,
    val completedCount: Int,
)

data class CreateProjectInput(
    val name: String,
    val color: Int,
    val icon: String? = null,
    val description: String? = null,
    val parentId: ProjectId? = null,
    val userId: String,
)
