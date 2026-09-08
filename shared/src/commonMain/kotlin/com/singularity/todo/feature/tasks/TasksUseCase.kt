package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.platform.Clock

// Keep: has domain validation + clock injection
class CreateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock
) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> {
        val validated: Either<AppError.Validation, CreateTaskInput> = TasksDomain.createInput(
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

        val task = TasksDomain.buildTask(
            input = (validated as Either.Right).value,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        return repo.create(task).map { task.id }
    }
}

// Keep: has domain timestamp update
class UpdateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock
) {
    /** Full-entity update. */
    suspend operator fun invoke(task: Task): Result<Unit> {
        val updated = task.copy(updatedAt = clock.now())
        return repo.update(updated)
    }

    /**
     * Read-modify-write update for atomic partial updates.
     */
    suspend operator fun invoke(id: TaskId, transform: (Task) -> Task): Result<Unit> {
        val current = repo.getById(id)
            ?: return Result.failure(AppError.NotFound("Task $id not found"))
        val updated = transform(current).copy(updatedAt = clock.now())
        return repo.update(updated)
    }
}

