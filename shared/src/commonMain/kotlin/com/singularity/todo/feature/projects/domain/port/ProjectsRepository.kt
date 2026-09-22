package com.singularity.todo.feature.projects.domain.port

import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Contract for projects persistence.
 */
interface ProjectsRepository :
    GenericUserScopedRepository<Project, ProjectId>,
    SoftDeletable<Project, ProjectId> {

    /** Single project by [id] for the current user. */
    fun observeProject(id: ProjectId): Flow<Project?>

    // ─── Domain methods ─────────────────────────────────────────────────────────

    /** All non-deleted projects with task counts for the current user. */
    fun observeProjectsWithCounts(): Flow<List<ProjectWithCountRow>>

    /** Children of a parent project, scoped to current user. */
    fun observeChildrenOf(parentId: ProjectId): Flow<List<Project>>

    /** Projects under a specific parent, scoped to current user. */
    fun observeByParent(parentId: ProjectId): Flow<List<Project>>

    /** Changes to a project (for inline-edit debounce). */
    fun changes(id: ProjectId): Flow<Project?>

    suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long)
    suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long)
    suspend fun findByIdempotencyKey(key: String): Project?
}
