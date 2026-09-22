package com.singularity.todo.feature.projects.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.ProjectsDomain
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository

/**
 * Uses [ProjectsDomain.validateCreateInput] for typed validation.
 * Matches the [com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase] pattern
 * (Either-to-Result, not require-throw).
 *
 * The ambient user ID is resolved internally via [ProfileAwareCurrentUser].
 */
class CreateProjectUseCase(
    private val repo: ProjectsRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    suspend operator fun invoke(input: CreateProjectInput): Result<ProjectId> {
        val validated: Either<AppError.Validation, CreateProjectInput> =
            ProjectsDomain.validateCreateInput(input)
        if (validated is Either.Left) return Result.failure(validated.error)

        val userId = currentUser.scopedUserId.value
        val now = clock.now()
        val project = ProjectsDomain.buildProject(
            input = (validated as Either.Right).value,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        return repo.create(project).map { it.id }
    }
}
