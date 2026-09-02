package com.singularity.todo.feature.projects

import java.util.UUID

@JvmInline
value class ProjectId(val value: String) {
    companion object {
        fun generate() = ProjectId(UUID.randomUUID().toString())
        fun fromString(value: String) = ProjectId(value)
    }
}

data class Project(
    val id: ProjectId,
    val name: String,
    val color: Int, // ARGB
    val icon: String? = null,
    val description: String? = null,
    val createdAt: kotlinx.datetime.Instant,
    val updatedAt: kotlinx.datetime.Instant,
    val isDefault: Boolean = false,
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val team: String? = null,
    val isDeleted: Boolean = false,
    val deletedAt: kotlinx.datetime.Instant? = null,
    val parentId: ProjectId? = null,
    val sortOrder: Int = 0,
    val isNotebook: Boolean = false,
    val externalId: String? = null,
    val userId: String
)

data class CreateProjectInput(
    val name: String,
    val color: Int,
    val icon: String? = null,
    val description: String? = null,
    val userId: String
)
