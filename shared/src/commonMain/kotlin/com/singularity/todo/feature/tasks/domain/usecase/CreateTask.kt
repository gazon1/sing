package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Creates a new task with domain validation and timestamp injection.
 */
class CreateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> {
        val validated: Either<AppError.Validation, CreateTaskInput> = TaskDomain.createInput(
            title = input.title,
            description = input.description,
            priority = input.priority,
            kind = input.kind,
            projectId = input.projectId,
            tagIds = input.tagIds,
            dueDate = input.dueDate,
            dueTime = input.dueTime,
            someday = input.someday,
            userId = input.userId,
        )
        if (validated is Either.Left) return Result.failure(validated.error)

        val task = TaskDomain.buildTask(
            input = (validated as Either.Right).value,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        return repo.create(task).map { it.id }
    }
}
