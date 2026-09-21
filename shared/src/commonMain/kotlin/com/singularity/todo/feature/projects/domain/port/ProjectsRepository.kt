package com.singularity.todo.feature.projects.domain.port

import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.ids.UserId
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

    // ─── Explicit userId overloads (kept for callers that pass userId explicitly) ──

    /** All projects for a specific [userId]. */
    fun watchProjects(userId: UserId): Flow<List<Project>>

    /** Single project by [id] for a specific [userId]. */
    fun watchProject(id: ProjectId): Flow<Project?>

    // ─── Domain methods ─────────────────────────────────────────────────────────

    /** All non-deleted projects with task counts for the current user. */
    fun observeProjectsWithCounts(): Flow<List<ProjectWithCountRow>>

    /** Children of a parent project, scoped to current user. */
    fun watchChildrenOf(parentId: ProjectId): Flow<List<Project>>

    /** Projects with task counts (total + completed) for a specific [userId]. */
    fun watchProjectsWithCounts(userId: UserId): Flow<List<ProjectWithCountRow>>

    /** Projects under a specific parent, scoped to current user. */
    fun watchByParent(parentId: ProjectId): Flow<List<Project>>

    /** Changes to a project (for inline-edit debounce). */
    fun changes(id: ProjectId): Flow<Project?>

    suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long)
    suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long)
    suspend fun findByIdempotencyKey(key: String): Project?
}
