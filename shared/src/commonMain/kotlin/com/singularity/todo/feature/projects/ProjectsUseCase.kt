package com.singularity.todo.feature.projects

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock

class CreateProjectUseCase(
    private val repo: ProjectsRepository,
    private val clock: Clock,
) {
    /**
     * Uses [ProjectsDomain.validateCreateInput] for typed validation.
     * Matches the [com.singularity.todo.feature.tasks.CreateTaskUseCase] pattern
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
        return repo.create(project).map { project.id }
    }
}

// Keep: has domain timestamp update
class UpdateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    /** Full-entity update. */
    suspend operator fun invoke(project: Project): Result<Unit> = runCatchingResult {
        repo.update(project.copy(updatedAt = clock.now())).getOrThrow()
    }

    /**
     * Read-modify-write update.
     * Enables atomic partial updates without a prior read in the caller.
     */
    suspend operator fun invoke(id: ProjectId, transform: (Project) -> Project): Result<Unit> =
        runCatchingResult {
            val current = repo.getById(id)
                ?: throw AppError.NotFound("Project $id not found")
            val updated = transform(current).copy(updatedAt = clock.now())
            repo.update(updated).getOrThrow()
        }
}
