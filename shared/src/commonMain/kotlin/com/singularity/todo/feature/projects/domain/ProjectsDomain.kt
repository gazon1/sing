package com.singularity.todo.feature.projects.domain

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.text.visibleLength
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
     * Maximum project name length, counted in visible characters.
     *
     * Not `String.length` — see [visibleLength]. An emoji counts once here, which
     * is what a person reading "max 50 characters" expects.
     */
    const val MAX_NAME_LENGTH: Int = 50

    /**
     * Validates a project name. The only copy of this rule in the codebase.
     *
     * Both the write path ([validateCreateInput]) and the editor
     * (`ProjectEditorViewModel`) call this. They used to hold private copies of
     * the same three lines, so the editor could accept a name the domain then
     * rejected — or, once one copy was fixed for emoji, keep rejecting it.
     *
     * @return `null` when the name is acceptable, otherwise the reason it is not.
     */
    fun validateName(name: String): AppError.Validation? = when {
        name.isBlank() -> AppError.Validation("Name cannot be blank", code = "project.name.blank")

        name.visibleLength() > MAX_NAME_LENGTH -> AppError.Validation(
            "Name too long (max $MAX_NAME_LENGTH characters)",
            code = "project.name.too_long",
        )

        else -> null
    }

    /**
     * Validates a [CreateProjectInput].
     * @return [Either.Right] with validated input on success, [Either.Left] with [AppError.Validation] on failure.
     */
    fun validateCreateInput(input: CreateProjectInput): Either<AppError.Validation, CreateProjectInput> {
        validateName(input.name)?.let { return Either.Left(it) }
        if (input.color ushr 24 == 0) {
            return Either.Left(
                AppError.Validation(
                    "Invalid color — alpha channel must be set",
                    code = "project.color.alpha_unset",
                ),
            )
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
