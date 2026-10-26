package com.singularity.todo.feature.projects.data

import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.ProjectInheritedTagGroupDao
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.projectWithInheritance
import com.singularity.todo.core.database.replaceInheritedTagGroups
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.database.toLocalDateOrNull
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.database.UnitOfWork

/**
 * Room-backed production [ProjectsRepository].
 */
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val inheritedTagGroupDao: ProjectInheritedTagGroupDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
    private val unitOfWork: UnitOfWork,
) : ProjectsRepository {

    // ── GenericUserScopedRepository ────────────────────────────────────────────

    override fun observeAll(): Flow<List<Project>> = currentUser.observeForCurrentUser { uid ->
        projectDao.watchAll(uid.value).map { list -> list.map { it.toProject() } }
    }

    override fun observe(id: ProjectId): Flow<Project?> = currentUser.observeForCurrentUser { uid ->
        projectDao.watchByIdForUser(id.value, uid.value).map { it?.toProject() }
    }

    override suspend fun get(id: ProjectId): Project? {
        val uid = currentUser.scopedUserId.value
        return projectDao.getByIdForUser(id.value, uid.value)?.toProject()
    }

    override suspend fun create(item: Project): Result<Project> = runCatchingCancellable {
        unitOfWork.write {
            currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
            projectDao.upsert(item.toEntity())
            // The row must exist before the join table can reference it: both DAO
            // statements scope themselves through `projects.user_id`, so an upsert
            // that has not landed yet would silently write nothing.
            replaceInheritedTagGroups(inheritedTagGroupDao, item)
            item.also { syncRepository.enqueue(it) }
        }
    }

    override suspend fun update(item: Project): Result<Project> = runCatchingCancellable {
        unitOfWork.write {
            currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
            // Re-stamp after the guard, as Tasks and Notes now do: the guard has
            // established that userId is current-or-anonymous, so normalising cannot
            // lose information, whereas upserting a caller's anonymous id verbatim
            // would orphan the row.
            val toUpdate = item.copy(userId = currentUser.scopedUserId.value)
            projectDao.upsert(toUpdate.toEntity())
            replaceInheritedTagGroups(inheritedTagGroupDao, toUpdate)
            toUpdate.also { syncRepository.enqueue(it) }
        }
    }

    /**
     * Re-reads [id] and pushes that state to the sync outbox.
     *
     * The narrow methods below write through a targeted `UPDATE`, so the caller's
     * project is stale by the time the write lands. Re-reading makes the pushed
     * payload match the database — including for deletes, which propagate as
     * state (`isDeleted`) rather than as a tombstone, since `buildPatch` already
     * ships the full snapshot.
     */
    private suspend fun enqueueFresh(id: ProjectId) {
        // `projectWithInheritance`, not `getByIdForUser(...).toProject()`: the join
        // table is part of the project document, and a patch built without it would
        // assert the project inherits nothing — silently erasing it on the server
        // and on every other device, on a rename.
        val project = projectWithInheritance(
            projectDao,
            inheritedTagGroupDao,
            id.value,
            currentUser.scopedUserId.value.value,
        ) ?: return
        syncRepository.enqueue(project)
    }

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(document: ProjectDocument): Project {
        projectDao.upsert(document.project.toEntity())
        // The inheritance is not a column of the row: it lives in a
        // cross-reference table, and the table is replaced only when the
        // document makes a claim about it. A document that says nothing about
        // the inherited groups leaves the groups the project has — the write
        // that always replaced the table read that silence as "the project
        // inherits nothing", and the groups went away on this device while the
        // pull reported success.
        val claim = document.inheritedTagGroupIds
        if (claim != null) {
            replaceInheritedTagGroups(
                inheritedTagGroupDao,
                document.project.copy(inheritedTagGroupIds = claim),
            )
        }
        // What the project inherits after the write: the claim if there was one,
        // and the groups that are still there if there was none.
        val inherited = claim ?: inheritedTagGroupDao.getByProject(
            document.project.id.value,
            document.project.userId.value,
        ).map(TagGroupId::fromString).toSet()
        return document.project.copy(inheritedTagGroupIds = inherited)
    }

    override suspend fun delete(id: ProjectId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val ts = clock.now().toEpochMilliseconds()
            val uid = currentUser.scopedUserId.value.value
            val rows = projectDao.softDeleteForUser(id.value, ts, uid)
            require(rows > 0) { "Project $id not found or not owned by user" }
            enqueueFresh(id)
        }
    }

    // ── SoftDeletable ─────────────────────────────────────────────────────────

    /**
     * Hard-deletes every project of the active profile, deleted ones included.
     *
     * ## Why the delete travels to the server as a deletion
     *
     * The rows are gone locally — that is the point of the wipe — but the server
     * still holds them, and this codebase has no tombstone: a project's deletion
     * travels as state (`isDeleted`), which is what `softDelete` pushes. So every
     * removed project is enqueued as deleted, and the server's copy says what
     * the local wipe means. Enqueueing nothing would leave the server calling
     * them live, and the next pull would bring them back.
     *
     * The snapshot is taken *before* the delete: afterwards the rows are gone and
     * there is nothing left to describe.
     */
    override suspend fun deleteAllForCurrentProfile(): Result<Int> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val doomed = projectDao.listAllForUser(uid)
            // The inherited tag groups are part of the project document, and the
            // join table is emptied with the projects — so the sets are read
            // while they still exist.
            val inheritance = inheritedTagGroupDao.listAllForUser(uid)
                .groupBy { it.projectId }
                .mapValues { (_, rows) -> rows.map { TagGroupId.fromString(it.tagGroupId) }.toSet() }
            val now = clock.now()
            // The join table first: its rows carry no owner column of their own,
            // so the projects they belong to can be deleted without taking them
            // along.
            inheritedTagGroupDao.deleteAllForUserProjects(uid)
            val removed = projectDao.deleteAllForUser(uid)
            doomed.forEach { row ->
                val project = row.toProject().copy(
                    inheritedTagGroupIds = inheritance[row.id].orEmpty(),
                )
                syncRepository.enqueue(
                    project.copy(isDeleted = true, deletedAt = project.deletedAt ?: now),
                )
            }
            removed
        }
    }

    override suspend fun restore(id: ProjectId): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val ts = clock.now().toEpochMilliseconds()
            val uid = currentUser.scopedUserId.value.value
            val rows = projectDao.restoreForUser(id.value, ts, uid)
            require(rows > 0) { "Project $id not found or not owned by user" }
            enqueueFresh(id)
        }
    }

    // ── Domain methods ───────────────────────────────────────────────────────

    override fun observeProject(id: ProjectId): Flow<Project?> {
        val uid = currentUser.scopedUserId.value
        return projectDao.watchByIdForUser(id.value, uid.value).map { it?.toProject() }
    }

    override fun observeProjectsWithCounts(): Flow<List<com.singularity.todo.core.database.ProjectWithCountRow>> =
        currentUser.observeForCurrentUser { uid ->
            projectDao.watchAllWithCounts(uid.value)
        }

    override fun observeAllIncludingClosed(): Flow<List<Project>> =
        currentUser.observeForCurrentUser { uid ->
            projectDao.watchAllIncludingClosed(uid.value).map { list -> list.map { it.toProject() } }
        }

    override fun observeClosed(): Flow<List<Project>> =
        currentUser.observeForCurrentUser { uid ->
            projectDao.watchClosedForUser(uid.value).map { list ->
                list.map { it.toProject() }
            }
        }

    override fun observeChildrenOf(parentId: ProjectId): Flow<List<Project>> =
        currentUser.observeForCurrentUser { uid ->
            projectDao.watchByParentForUser(parentId.value, uid.value).map { list ->
                list.map { it.toProject() }
            }
        }

    override fun observeByParent(parentId: ProjectId): Flow<List<Project>> {
        val uid = currentUser.scopedUserId.value
        return projectDao.watchByParentForUser(parentId.value, uid.value).map { list ->
            list.map { it.toProject() }
        }
    }

    override fun changes(id: ProjectId): Flow<Project?> {
        val uid = currentUser.scopedUserId.value
        return projectDao.watchByIdForUser(id.value, uid.value).map { it?.toProject() }
    }

    override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {
        val uid = currentUser.scopedUserId.value.value
        unitOfWork.write {
            val rows = projectDao.setParentForUser(id.value, parentId?.value, updatedAt, uid)
            require(rows > 0) { "Project $id not found or not owned by user" }
            // parentId is a serialised field of Project, so the reparent must sync.
            enqueueFresh(id)
        }
    }

    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {
        val uid = currentUser.scopedUserId.value.value
        unitOfWork.write {
            val rows = projectDao.setSortOrderForUser(id.value, sortOrder, updatedAt, uid)
            require(rows > 0) { "Project $id not found or not owned by user" }
            // sortOrder is a serialised field of Project.
            enqueueFresh(id)
        }
    }

    override suspend fun findByIdempotencyKey(key: String): Project? {
        val uid = currentUser.scopedUserId.value.value
        return projectDao.findByIdempotencyKeyForUser(key, uid)?.toProject()
    }

    override suspend fun close(id: ProjectId, closedAt: Long): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val rows = projectDao.closeForUser(id.value, closedAt, uid)
            require(rows > 0) { "Project $id not found or not owned by user" }
            // closedAt is a serialised field of Project, so the close has to sync.
            enqueueFresh(id)
        }
    }

    override suspend fun reopen(id: ProjectId, updatedAt: Long): Result<Unit> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value.value
            val rows = projectDao.reopenForUser(id.value, updatedAt, uid)
            if (rows > 0) {
                // closedAt is a serialised field of Project, so the open has to sync too.
                enqueueFresh(id)
            } else if (projectDao.getByIdForUser(id.value, uid) == null) {
                throw IllegalArgumentException("Project $id not found or not owned by user")
            }
            // No rows, but the project is there: it was opened in the meantime —
            // on another device, or by another task of the same project coming
            // back to work. The state the caller asked for is the state the
            // server already has, so the lost race resolves to that state
            // rather than to an error about a write nobody needed.
        }
    }
}

internal fun ProjectEntity.toProject(): Project = Project(
    id = ProjectId.fromString(id),
    name = name,
    color = color,
    icon = icon,
    description = description,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    isDefault = isDefault,
    dueDate = dueDate.toLocalDateOrNull(),
    team = team,
    isDeleted = isDeleted,
    deletedAt = deletedAt.toInstantOrNull(),
    closedAt = closedAt.toInstantOrNull(),
    parentId = parentId?.let { ProjectId.fromString(it) },
    sortOrder = sortOrder,
    idempotencyKey = idempotencyKey,
    externalId = externalId,
    userId = UserId(userId),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

internal fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id.value,
    userId = userId.value,
    name = name,
    color = color,
    icon = icon,
    description = description,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    isDefault = isDefault,
    dueDate = dueDate?.toString(),
    team = team,
    isDeleted = isDeleted,
    deletedAt = deletedAt?.toEpochMillisOrNull(),
    closedAt = closedAt?.toEpochMillisOrNull(),
    parentId = parentId?.value,
    sortOrder = sortOrder,
    idempotencyKey = idempotencyKey,
    externalId = externalId,
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
