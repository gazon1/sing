package com.singularity.todo.feature.projects

import com.singularity.todo.core.database.ProjectDao
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for projects persistence.
 */
interface ProjectsRepository {
    fun watchProjects(userId: String): Flow<List<Project>>
    fun watchProject(id: ProjectId): Flow<Project?>
    suspend fun create(project: Project): Result<Unit>
    suspend fun update(project: Project): Result<Unit>
    suspend fun delete(id: ProjectId): Result<Unit>
}

/**
 * Room-backed production [ProjectsRepository].
 */
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val clock: Clock
) : ProjectsRepository {
    override fun watchProjects(userId: String): Flow<List<Project>> {
        return projectDao.watchAll(userId).map { list -> list.map { it.toProject() } }
    }

    override fun watchProject(id: ProjectId): Flow<Project?> {
        return projectDao.watchById(id.value).map { it?.toProject() }
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

private fun ProjectEntity.toProject(): Project = Project(
    id = ProjectId.fromString(id),
    name = name,
    color = color,
    icon = icon,
    description = description,
    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt),
    updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(updatedAt),
    isDefault = isDefault,
    dueDate = dueDate?.let { kotlinx.datetime.LocalDate.parse(it) },
    team = team,
    isDeleted = isDeleted,
    deletedAt = deletedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
    parentId = parentId?.let { ProjectId.fromString(it) },
    sortOrder = sortOrder,
    isNotebook = isNotebook,
    externalId = externalId,
    userId = userId
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id.value,
    userId = userId,
    name = name,
    color = color,
    icon = icon,
    description = description,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    isDefault = isDefault,
    dueDate = dueDate?.toString(),
    team = team,
    isDeleted = isDeleted,
    deletedAt = deletedAt?.toEpochMilliseconds(),
    parentId = parentId?.value,
    sortOrder = sortOrder,
    isNotebook = isNotebook,
    externalId = externalId
)
