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
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed production [ProjectsRepository].
 */
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
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

    override suspend fun create(item: Project): Result<Project> = runCatching {
        val currentUid = currentUser.scopedUserId.value
        // Cross-user guard: fail loud rather than silently write to the wrong user.
        val toInsert = if (item.userId == currentUid) {
            item
        } else {
            throw IllegalStateException(
                "Cross-user create attempted: entity.userId=${item.userId}, current=$currentUid",
            )
        }
        projectDao.upsert(toInsert.toEntity())
        toInsert.also { syncRepository.enqueue(it) }
    }

    override suspend fun update(item: Project): Result<Project> = runCatching {
        projectDao.upsert(item.toEntity())
        item.also { syncRepository.enqueue(it) }
    }

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(project: Project): Project {
        projectDao.upsert(project.toEntity())
        return project
    }

    override suspend fun delete(id: ProjectId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        val uid = currentUser.scopedUserId.value.value
        val rows = projectDao.softDeleteForUser(id.value, ts, uid)
        require(rows > 0) { "Project $id not found or not owned by user" }
    }

    // ── SoftDeletable ─────────────────────────────────────────────────────────

    override suspend fun restore(id: ProjectId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        val uid = currentUser.scopedUserId.value.value
        val rows = projectDao.restoreForUser(id.value, ts, uid)
        require(rows > 0) { "Project $id not found or not owned by user" }
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
        projectDao.setParentForUser(id.value, parentId?.value, updatedAt, uid)
    }

    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {
        val uid = currentUser.scopedUserId.value.value
        projectDao.setSortOrderForUser(id.value, sortOrder, updatedAt, uid)
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
