package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Clock

/**
 * Creates a task from a [TaskDraft] (editor UI state) — the entry point used by
 * `TaskCreateViewModel` when the user taps Save.
 *
 * Real domain logic, not pass-through:
 *  - translates the editor's [DueDateOption] into a concrete [LocalDate] (or null);
 *  - runs [TaskDomain.createInput] for validation (the single source of truth);
 *  - converts an [AppError.Validation] into the [Either.Left] side so callers can
 *    surface it as a UI message without inspecting an exception;
 *  - converts any persistence failure from [TaskRepository.create] into
 *    [AppError.Persistence] so the caller doesn't have to inspect exceptions either.
 *
 * The ambient user ID is resolved internally via [ProfileAwareCurrentUser].
 *
 * Use this when you have a draft (e.g. from `TaskCreateViewModel.save`). For an
 * already-validated [CreateTaskInput] coming from detail view, use
 * [CreateTaskUseCase] directly.
 */
class CreateTaskFromDraftUseCase(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    suspend operator fun invoke(draft: TaskDraft): Either<AppError, TaskId> {
        val dueDate: LocalDate? = when (val option = draft.dueDate) {
            is DueDateOption.Custom -> option.date

            DueDateOption.Today -> todayInSystemZone()

            DueDateOption.Tomorrow -> {
                val today = todayInSystemZone()
                today.plus(1, DateTimeUnit.DAY)
            }

            DueDateOption.None -> null
        }

        fun resolveDate(option: DueDateOption): LocalDate? = when (option) {
            is DueDateOption.Custom -> option.date
            DueDateOption.Today -> todayInSystemZone()
            DueDateOption.Tomorrow -> todayInSystemZone().plus(1, DateTimeUnit.DAY)
            DueDateOption.None -> null
        }

        val validated: Either<AppError.Validation, CreateTaskInput> = TaskDomain.createInput(
            title = draft.title,
            description = draft.description.ifBlank { null },
            priority = draft.priority,
            kind = TaskKind.Task,
            projectId = draft.projectId?.let { ProjectId.fromString(it) },
            parentTaskId = null,
            tagIds = draft.tagIds.map { TagId.fromString(it) },
            dueDate = dueDate,
            dueTime = draft.dueTime,
            startDate = resolveDate(draft.startDate),
            startTime = draft.startTime,
            endDate = resolveDate(draft.endDate),
            endTime = draft.endTime,
            accentColor = draft.accentColor,
            emoji = draft.emoji,
            someday = false,
        )
        return when (validated) {
            is Either.Left -> Either.Left(validated.error)

            is Either.Right -> {
                // Mint the id once so the caller can observe it (logs, snackbar
                // deep links) before / independent of repo.create.
                val userId = currentUser.scopedUserId.value
                val now = clock.now()
                val taskId = TaskDomain.generateTaskId()
                val task = TaskDomain.buildTask(
                    input = validated.value,
                    id = taskId,
                    createdAt = now,
                    updatedAt = now,
                    userId = userId,
                )
                repo.create(task).fold(
                    onSuccess = { Either.Right(taskId) },
                    onFailure = { e ->
                        Either.Left(AppError.Persistence(e.toMessage()))
                    },
                )
            }
        }
    }
}
