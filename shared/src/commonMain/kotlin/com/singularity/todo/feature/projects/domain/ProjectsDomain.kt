package com.singularity.todo.feature.projects.domain

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
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
     * Checks the 1-level hierarchy invariant: a project being set as a parent
     * must itself be a root project (parentId == null).
     *
     * @param parent the candidate parent project, or null for root-level.
     * @return [Either.Right] with [parent] on success, [Either.Left] if parent already has a parent.
     */
    fun assertParentIsRoot(
        parent: Project?,
    ): Either<AppError.Validation, Project?> {
        if (parent == null) return Either.Right(null)
        return if (parent.parentId == null) {
            Either.Right(parent)
        } else {
            Either.Left(
                AppError.Validation(
                    "Only root projects can be parents. \"${parent.name}\" is already a sub-project."
                )
            )
        }
    }

    /**
     * Builds a [Project] from validated input.
     * Pure function — no side effects.
     */
    fun buildProject(
        input: CreateProjectInput,
        id: ProjectId = ProjectId.generate(),
        createdAt: Instant,
        updatedAt: Instant,
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
        userId = input.userId,
    )
}
