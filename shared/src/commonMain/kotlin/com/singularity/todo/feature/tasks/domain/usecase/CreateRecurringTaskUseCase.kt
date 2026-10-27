package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Creates a recurring task — a rule with a series id and a materialised window —
 * from an already-shaped [CreateTaskInput]. The AI/MCP counterpart of the create
 * screen's recurring door: where [CreateTaskUseCase] refuses a rule on a row that
 * stands alone, this one mints the series and fills the window.
 *
 * The invariant failure that [TaskRepository.create] raises for a rule without a
 * series is mapped here to [AppError.Validation] with a stable code, rather than
 * leaking as a raw [IllegalArgumentException].
 */
class CreateRecurringTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val unitOfWork: UnitOfWork,
    private val materialize: MaterializeRecurringSeriesUseCase,
) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> {
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
        val taskInput = (validated as Either.Right).value
        val rule = taskInput.recurrence
        if (rule == null) {
            val task = TaskDomain.buildTask(
                input = taskInput,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                userId = userId,
            )
            return repo.create(task).map { it.id }
        }

        return runCatchingCancellable {
            unitOfWork.write {
                val seriesId = TaskSeriesId.generate()
                val current = TaskDomain.buildTask(
                    input = taskInput,
                    id = TaskId.generate(),
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                    userId = userId,
                ).copy(seriesId = seriesId)
                repo.createSeries(listOf(current)).getOrThrow()
                val anchor: LocalDate = current.dueDate ?: clock.now().toLocalDateTime(TimeZone.UTC).date
                materialize(template = current, rule = rule, anchor = anchor).getOrThrow()
                current.id
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error is IllegalArgumentException) {
                    Result.failure(
                        AppError.Validation(
                            message = error.message ?: "",
                            code = "task.recurring.invariant",
                            cause = error,
                        ),
                    )
                } else {
                    Result.failure(error)
                }
            },
        )
    }
}
