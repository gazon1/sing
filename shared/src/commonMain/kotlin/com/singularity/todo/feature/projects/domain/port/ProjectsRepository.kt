package com.singularity.todo.feature.projects.domain.port

import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Contract for projects persistence.
 */
interface ProjectsRepository {
    fun watchProjects(userId: UserId): Flow<List<Project>>
    fun watchProject(id: ProjectId): Flow<Project?>

    /** Suspend version for one-shot reads (e.g. in use cases). */
    suspend fun getById(id: ProjectId): Project?

    /** Emits a new value whenever the project changes (used for inline-edit debounce). */
    fun changes(id: ProjectId): Flow<Project?>

    /** Projects with task counts (total + completed), for list screens. */
    fun watchProjectsWithCounts(userId: UserId): Flow<List<ProjectWithCountRow>>
    fun watchByParent(parentId: ProjectId): Flow<List<Project>>
    suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long)
    suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long)
    suspend fun restore(id: ProjectId): Result<Unit>
    suspend fun findByIdempotencyKey(key: String): Project?
    suspend fun create(project: Project): Result<Unit>
    suspend fun update(project: Project): Result<Unit>
    suspend fun delete(id: ProjectId): Result<Unit>
}
