package com.singularity.todo.feature.projects.domain.port

import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Contract for projects persistence.
 */
interface ProjectsRepository :
    GenericUserScopedRepository<Project, ProjectId>,
    SoftDeletable<Project, ProjectId> {

    /**
     * Upserts a project from a remote sync event.
     * Does NOT emit repository-level change events — caller handles observability.
     * Used by pull handlers in [com.singularity.todo.core.sync.SyncBootstrapper].
     */
    suspend fun upsert(project: Project): Project

    /**
     * Hard-deletes every project the active profile owns — deleted projects
     * included — and reports how many rows went.
     *
     * ## Why this exists
     *
     * A sweep that enumerates [observeAll] cannot empty a profile: `observeAll`
     * reports the live projects only, so every deleted project survives the
     * sweep and turns up in the next run — the previous run's state, seen by
     * the next one.
     *
     * ## What it does not do
     *
     * It touches nothing outside the active profile: the scope is the current
     * profile's scoped user id, which is what every row of that profile
     * carries.
     *
     * @return the number of rows removed.
     */
    suspend fun deleteAllForCurrentProfile(): Result<Int>

    /** Single project by [id] for the current user. */
    fun observeProject(id: ProjectId): Flow<Project?>

    // ─── Domain methods ─────────────────────────────────────────────────────────

    /** All non-deleted projects with task counts for the current user. */
    fun observeProjectsWithCounts(): Flow<List<ProjectWithCountRow>>

    /** Children of a parent project, scoped to current user. */
    fun observeChildrenOf(parentId: ProjectId): Flow<List<Project>>

    /** Projects under a specific parent, scoped to current user. */
    fun observeByParent(parentId: ProjectId): Flow<List<Project>>

    /**
     * Observes the projects whose name matches [query].
     *
     * The lookup goes through the full-text index, so the cost of the search
     * does not grow with the number of projects. The match is a *word-prefix*
     * match — the semantics of the index — and the terms are combined the way
     * [query] says: every term, or any of them.
     */
    fun observeByTextMatch(query: TextMatchQuery): Flow<List<Project>>

    /** Changes to a project (for inline-edit debounce). */
    fun changes(id: ProjectId): Flow<Project?>

    suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long)
    suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long)
    suspend fun findByIdempotencyKey(key: String): Project?
}
