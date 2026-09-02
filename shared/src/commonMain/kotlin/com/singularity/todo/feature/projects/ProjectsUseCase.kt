package com.singularity.todo.feature.projects

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow

class GetProjectsUseCase(private val repo: ProjectsRepository) {
    operator fun invoke(userId: String): Flow<List<Project>> = repo.watchProjects(userId)
}

class GetProjectUseCase(private val repo: ProjectsRepository) {
    operator fun invoke(id: ProjectId): Flow<Project?> = repo.watchProject(id)
}

class CreateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateProjectInput): Result<ProjectId> = runCatchingResult {
        require(input.name.isNotBlank()) { throw AppError.Validation("Name cannot be blank") }
        require(input.name.length <= 50) { throw AppError.Validation("Name too long") }
        require(input.color ushr 24 != 0) { throw AppError.Validation("Invalid color") }
        val now = clock.now()
        val project = Project(
            id = ProjectId.generate(),
            name = input.name.trim(),
            color = input.color,
            icon = input.icon,
            description = input.description,
            createdAt = now,
            updatedAt = now,
            userId = input.userId
        )
        repo.create(project).getOrThrow()
        project.id
    }
}

class UpdateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    suspend operator fun invoke(project: Project): Result<Unit> = runCatchingResult {
        repo.update(project.copy(updatedAt = clock.now())).getOrThrow()
    }
}

class DeleteProjectUseCase(private val repo: ProjectsRepository) {
    suspend operator fun invoke(id: ProjectId): Result<Unit> = runCatchingResult {
        repo.delete(id).getOrThrow()
    }
}
