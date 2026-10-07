package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
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
    private val timeZone: com.singularity.todo.core.platform.TimeZoneProvider,
    private val currentUser: ProfileAwareCurrentUser,
    private val checklistRepository: ChecklistRepository,
    private val attachmentRepository: AttachmentRepository,
) {
    suspend operator fun invoke(draft: TaskDraft): Either<AppError, TaskId> {
        // `today` once, then used by both resolutions below. The class already
        // took a `Clock` and still called the global `todayInSystemZone()` in four
        // places, so a test could inject a clock and get the host's date anyway --
        // the injection was decorative for exactly the values that matter (#91).
        //
        // The zone is required, not defaulted: "Today" has to mean the user's
        // today, and a default here would be the host's, which is what made this
        // non-deterministic between machines even with a fixed clock.
        val today: LocalDate = todayAt(clock, timeZone.current())

        fun resolveDate(option: DueDateOption): LocalDate? = when (option) {
            is DueDateOption.Custom -> option.date
            DueDateOption.Today -> today
            DueDateOption.Tomorrow -> today.plus(1, DateTimeUnit.DAY)
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
            dueDate = resolveDate(draft.dueDate),
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
                    onSuccess = {
                        // Persist checklist items
                        if (draft.checklist.isNotEmpty()) {
                            val items = draft.checklist.map { draftItem ->
                                ChecklistItem(
                                    id = ChecklistItemId.fromString(draftItem.id),
                                    taskId = taskId.value,
                                    title = draftItem.text,
                                    isCompleted = draftItem.isChecked,
                                )
                            }
                            checklistRepository.createBatch(taskId.value, items)
                        }
                        // Persist URL attachments
                        for (draftAtt in draft.attachments) {
                            attachmentRepository.addUrlAttachment(taskId, draftAtt.url, draftAtt.title)
                        }
                        Either.Right(taskId)
                    },
                    onFailure = { e ->
                        // The cause travels with the error. `AppError.Unknown`'s own KDoc
                        // says every producer attaches one, and dropping it here is what
                        // made CreateTaskFlowTest's save failure undiagnosable: the screen
                        // showed a generic message, the failure bundle had no stack, and
                        // the cause was a lateinit that naming alone did not locate.
                        Either.Left(
                            AppError.Persistence(
                                message = e.toMessage(),
                                code = "task.draft.persist_failed",
                                cause = e,
                            ),
                        )
                    },
                )
            }
        }
    }
}
