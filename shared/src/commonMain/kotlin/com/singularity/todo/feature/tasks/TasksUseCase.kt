package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.TagId

// Keep: has domain validation + clock injection
class CreateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock
) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> = runCatchingResult {
        TasksDomain.validateTitle(input.title)

        val now = clock.now()
        val task = TasksDomain.buildTask(input, createdAt = now, updatedAt = now)

        repo.create(task).getOrThrow()
        task.id
    }
}

// Keep: has domain timestamp update
class UpdateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock
) {
    suspend operator fun invoke(task: Task): Result<Unit> = runCatchingResult {
        val updated = task.copy(updatedAt = clock.now())
        repo.update(updated).getOrThrow()
    }
}

// Keep: has multi-step repository logic (clear + re-add cross-refs)
class SetTagsUseCase(private val repo: TaskRepository) {
    suspend operator fun invoke(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatchingResult {
        repo.setTags(taskId, tagIds).getOrThrow()
    }
}
