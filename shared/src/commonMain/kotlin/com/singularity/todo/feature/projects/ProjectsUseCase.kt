package com.singularity.todo.feature.projects

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock

// Keep: has validation (require without throw) + domain timestamp + ProjectId generation
class CreateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateProjectInput): Result<ProjectId> = runCatchingResult {
        require(input.name.isNotBlank()) { AppError.Validation("Name cannot be blank") }
        require(input.name.length <= 50) { AppError.Validation("Name too long") }
        require(input.color ushr 24 != 0) { AppError.Validation("Invalid color") }
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

// Keep: has domain timestamp update
class UpdateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    suspend operator fun invoke(project: Project): Result<Unit> = runCatchingResult {
        repo.update(project.copy(updatedAt = clock.now())).getOrThrow()
    }
}
