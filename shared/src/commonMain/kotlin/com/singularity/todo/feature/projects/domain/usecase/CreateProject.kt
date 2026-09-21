package com.singularity.todo.feature.projects.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.domain.ProjectsDomain
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository

class CreateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    /**
     * Uses [ProjectsDomain.validateCreateInput] for typed validation.
     * Matches the [com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase] pattern
     * (Either-to-Result, not require-throw).
     */
    suspend operator fun invoke(input: CreateProjectInput): Result<ProjectId> {
        val validated: Either<AppError.Validation, CreateProjectInput> =
            ProjectsDomain.validateCreateInput(input)
        if (validated is Either.Left) return Result.failure(validated.error)

        val now = clock.now()
        val project = ProjectsDomain.buildProject(
            input = (validated as Either.Right).value,
            createdAt = now,
            updatedAt = now,
        )
        return repo.create(project).map { it.id }
    }
}
