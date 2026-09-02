package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.TagId
import kotlinx.coroutines.flow.Flow

// ===== Use Cases =====

class GetTasksUseCase(private val repo: TaskRepository) {
    operator fun invoke(userId: UserId, filter: TaskFilter): Flow<List<Task>> = repo.watchTasks(userId, filter)
}

class GetTaskUseCase(private val repo: TaskRepository) {
    operator fun invoke(id: TaskId): Flow<Task?> = repo.watchTask(id)
}

class CreateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock
) {
    /**
     * Creates a new task.
     * Validates input using pure domain logic, then persists via repository.
     */
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> = runCatchingResult {
        // Pure domain validation - throws AppError.Validation if invalid
        TasksDomain.validateTitle(input.title)

        val now = clock.now()
        val task = TasksDomain.buildTask(input, createdAt = now, updatedAt = now)

        repo.create(task).getOrThrow()
        task.id
    }
}

class UpdateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock
) {
    suspend operator fun invoke(task: Task): Result<Unit> = runCatchingResult {
        val updated = task.copy(updatedAt = clock.now())
        repo.update(updated).getOrThrow()
    }
}

class DeleteTaskUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(id: TaskId): Result<Unit> = runCatchingResult {
        repo.softDelete(id).getOrThrow()
    }
}

class RestoreTaskUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(id: TaskId): Result<Unit> = runCatchingResult {
        repo.restore(id).getOrThrow()
    }
}

class ToggleCompleteUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(id: TaskId): Result<Unit> = runCatchingResult {
        repo.toggleComplete(id).getOrThrow()
    }
}

class SetTagsUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatchingResult {
        repo.setTags(taskId, tagIds).getOrThrow()
    }
}
