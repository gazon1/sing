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
    // ─── UserId-free observation (Phase 2 pattern) ───────────────────────────────

    /** All non-deleted projects for the current user. */
    fun observeAllForCurrentUser(): Flow<List<Project>>

    /** All non-deleted projects with task counts for the current user. */
    fun observeProjectsWithCountsForCurrentUser(): Flow<List<ProjectWithCountRow>>

    /** Single project by ID, scoped to current user. Returns null if not found or not owned. */
    fun watchProjectForCurrentUser(id: ProjectId): Flow<Project?>

    /** Suspend version for one-shot reads (e.g. in use cases). */
    suspend fun getByIdForCurrentUser(id: ProjectId): Project?

    /** Emits a new value whenever the project changes (used for inline-edit debounce). */
    fun changesForCurrentUser(id: ProjectId): Flow<Project?>

    /** Children of a parent project, scoped to current user. */
    fun watchChildrenOfForCurrentUser(parentId: ProjectId): Flow<List<Project>>

    // ─── Explicit userId overloads (Phase 3 migration target) ──────────────────

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
