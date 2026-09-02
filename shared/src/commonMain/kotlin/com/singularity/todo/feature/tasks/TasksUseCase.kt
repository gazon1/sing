package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.TagId
import kotlinx.coroutines.flow.Flow

class GetTasksUseCase(private val repo: TaskRepository) {
    operator fun invoke(userId: UserId, filter: TaskFilter): Flow<List<Task>> = repo.watchTasks(userId, filter)
}

class GetTaskUseCase(private val repo: TaskRepository) {
    operator fun invoke(id: TaskId): Flow<Task?> = repo.watchTask(id)
}

class CreateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> = runCatchingResult {
        require(input.title.isNotBlank()) { throw AppError.Validation("Title cannot be blank") }
        val now = clock.now()
        val task = Task(
            id = TaskId.generate(),
            title = input.title.trim(),
            description = input.description?.trim(),
            priority = input.priority,
            kind = input.kind,
            projectId = input.projectId,
            tags = input.tagIds,
            dueDate = input.dueDate,
            dueTime = input.dueTime,
            someday = input.someday,
            createdAt = now,
            updatedAt = now,
            userId = input.userId
        )
        repo.create(task).getOrThrow()
        task.id
    }
}

class UpdateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    suspend operator fun invoke(task: Task): Result<Unit> = runCatchingResult {
        repo.update(task.copy(updatedAt = clock.now())).getOrThrow()
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
