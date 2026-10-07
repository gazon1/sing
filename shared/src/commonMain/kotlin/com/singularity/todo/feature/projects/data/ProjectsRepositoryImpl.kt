package com.singularity.todo.feature.projects.data

import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.SyncColumns
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
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
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
        val row = projectDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value) ?: return
        syncRepository.enqueue(row.toProject())
    }

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(project: Project): Project {
        projectDao.upsert(project.toEntity())
        return project
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
    parentId = parentId?.value,
    sortOrder = sortOrder,
    idempotencyKey = idempotencyKey,
    externalId = externalId,
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
