package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlin.time.Clock

/**
 * Creates a new task with domain validation and timestamp injection.
 * The ambient user ID is resolved internally via [ProfileAwareCurrentUser].
 */
class CreateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> {
        // Every field of `input` must be forwarded here. `createInput` re-validates and
        // rebuilds a new CreateTaskInput, so any field not passed is dropped — and
        // `buildTask` then persists a task without it. `parentTaskId` was omitted here,
        // which silently turned "add subtask" into "add a top-level task".
        val validated: Either<AppError.Validation, CreateTaskInput> = TaskDomain.createInput(
            title = input.title,
            description = input.description,
            priority = input.priority,
            kind = input.kind,
            projectId = input.projectId,
            parentTaskId = input.parentTaskId,
            tagIds = input.tagIds,
            dueDate = input.dueDate,
            dueTime = input.dueTime,
            startDate = input.startDate,
            startTime = input.startTime,
            endDate = input.endDate,
            endTime = input.endTime,
            accentColor = input.accentColor,
            emoji = input.emoji,
            someday = input.someday,
            recurrence = input.recurrence,
        )
        if (validated is Either.Left) return Result.failure(validated.error)

        val userId = currentUser.scopedUserId.value
        val task = TaskDomain.buildTask(
            input = (validated as Either.Right).value,
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = userId,
        )
        return repo.create(task).map { it.id }
    }
}
