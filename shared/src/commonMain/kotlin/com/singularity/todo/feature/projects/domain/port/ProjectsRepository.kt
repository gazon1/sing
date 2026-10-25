package com.singularity.todo.feature.projects.domain.port

import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.repository.GenericUserScopedRepository
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Contract for projects persistence.
 */
// 13 functions: the project's whole contract in one place. Splitting it into
// a live-projects contract and a closed-projects contract would move a
// boundary nobody asked for — every caller that touches a project touches
// both sides of it.
@Suppress("TooManyFunctions")
interface ProjectsRepository :
    GenericUserScopedRepository<Project, ProjectId>,
    SoftDeletable<Project, ProjectId> {

    /**
     * Upserts a project from a remote sync event.
     * Does NOT emit repository-level change events — caller handles observability.
     * Used by pull handlers in [com.singularity.todo.core.sync.SyncBootstrapper].
     *
     * The document carries the claim it makes about the inheritance, and the
     * claim is what the write follows: a document that says nothing about the
     * inherited tag groups leaves the groups the project has, and a document
     * that says the project inherits nothing takes them away. The claim is
     * `null` in the first case and an empty set in the second, and the two are
     * not the same statement.
     *
     * @param document the project and the claim about its inheritance.
     * @return the project as it stands after the write.
     */
    suspend fun upsert(document: ProjectDocument): Project

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

    /**
     * The projects the user closed, most recently closed first.
     *
     * The mirror of [observeProjectsWithCounts]: a closed project leaves the
     * project list, and this is where it goes. Without it, a project closed by
     * mistake could only be got back through one of its tasks — and a project
     * with no tasks could not be got back at all.
     */
    fun observeClosed(): Flow<List<Project>>

    /** Children of a parent project, scoped to current user. */
    fun observeChildrenOf(parentId: ProjectId): Flow<List<Project>>

    /** Projects under a specific parent, scoped to current user. */
    fun observeByParent(parentId: ProjectId): Flow<List<Project>>

    /** Changes to a project (for inline-edit debounce). */
    fun changes(id: ProjectId): Flow<Project?>

    suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long)
    suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long)
    suspend fun findByIdempotencyKey(key: String): Project?

    /**
     * Closes a project: its work is finished.
     *
     * A closed project is not a deleted one. The row stays, the project stays
     * readable, and it leaves the project list.
     *
     * The write is a narrow UPDATE rather than a row rewrite — the project may
     * have been renamed elsewhere since this device read it, and rewriting the
     * row would put the old name back.
     *
     * @param id the project to close.
     * @param closedAt the moment the work was declared finished.
     */
    suspend fun close(id: ProjectId, closedAt: Long): Result<Unit>

    /**
     * Opens a closed project again: its work is not finished after all.
     *
     * The reverse of [close], and as narrow — a project that is not closed is
     * left alone.
     *
     * @param id the project to open.
     * @param updatedAt the moment the project was opened.
     */
    suspend fun reopen(id: ProjectId, updatedAt: Long): Result<Unit>
}
