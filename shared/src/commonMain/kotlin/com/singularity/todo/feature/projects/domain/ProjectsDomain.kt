package com.singularity.todo.feature.projects.domain

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlin.time.Instant

/**
 * Pure domain logic for project validation and creation.
 * No dependencies — fully testable without mocks or Compose.
 */
object ProjectsDomain {

    /**
     * Validates a [CreateProjectInput].
     * @return [Either.Right] with validated input on success, [Either.Left] with [AppError.Validation] on failure.
     */
    fun validateCreateInput(input: CreateProjectInput): Either<AppError.Validation, CreateProjectInput> {
        if (input.name.isBlank()) {
            return Either.Left(AppError.Validation("Name cannot be blank"))
        }
        if (input.name.length > 50) {
            return Either.Left(AppError.Validation("Name too long (max 50 characters)"))
        }
        if (input.color ushr 24 == 0) {
            return Either.Left(AppError.Validation("Invalid color — alpha channel must be set"))
        }
        return Either.Right(input)
    }

    /**
     * Builds a [Project] from validated input.
     * Pure function — no side effects.
     * @param userId The ambient user ID, resolved by the caller (use case / repository).
     */
    fun buildProject(
        input: CreateProjectInput,
        id: ProjectId = ProjectId.generate(),
        createdAt: Instant,
        updatedAt: Instant,
        userId: UserId,
    ): Project = Project(
        id = id,
        name = input.name.trim(),
        color = input.color,
        icon = input.icon,
        description = input.description,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDefault = false,
        dueDate = null,
        team = null,
        isDeleted = false,
        deletedAt = null,
        parentId = input.parentId,
        sortOrder = 0,
        idempotencyKey = null,
        externalId = null,
        userId = userId,
    )
}
