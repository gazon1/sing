package com.singularity.todo.feature.projects.data

import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.database.toLocalDateOrNull
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ids.UserId
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
    private val clock: Clock
) : ProjectsRepository {
    override fun watchProjects(userId: UserId): Flow<List<Project>> {
        return projectDao.watchAll(userId.value).map { list -> list.map { it.toProject() } }
    }

    override fun watchProject(id: ProjectId): Flow<Project?> {
        return projectDao.watchById(id.value).map { it?.toProject() }
    }

    override suspend fun getById(id: ProjectId): Project? {
        return projectDao.getById(id.value)?.toProject()
    }

    override fun changes(id: ProjectId): Flow<Project?> {
        return projectDao.watchById(id.value).map { it?.toProject() }
    }

    override fun watchProjectsWithCounts(userId: UserId): Flow<List<com.singularity.todo.core.database.ProjectWithCountRow>> {
        return projectDao.watchAllWithCounts(userId.value)
    }

    override fun watchByParent(parentId: ProjectId): Flow<List<Project>> {
        return projectDao.watchByParent(parentId.value).map { list -> list.map { it.toProject() } }
    }

    override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {
        projectDao.setParent(id.value, parentId?.value, updatedAt)
    }

    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {
        projectDao.setSortOrder(id.value, sortOrder, updatedAt)
    }

    override suspend fun restore(id: ProjectId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        projectDao.restore(id.value, ts)
    }

    override suspend fun findByIdempotencyKey(key: String): Project? {
        return projectDao.findByIdempotencyKey(key)?.toProject()
    }

    override suspend fun create(project: Project): Result<Unit> = runCatching {
        projectDao.upsert(project.toEntity())
    }

    override suspend fun update(project: Project): Result<Unit> = runCatching {
        projectDao.upsert(project.toEntity())
    }

    override suspend fun delete(id: ProjectId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        projectDao.softDelete(id.value, ts)
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
    userId = UserId(userId)
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
    externalId = externalId
)
