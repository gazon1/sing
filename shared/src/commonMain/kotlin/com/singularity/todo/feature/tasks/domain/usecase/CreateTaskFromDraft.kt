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
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesPlanner
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
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
 *  - collects the whole set of dates the draft carries — the primary one and the
 *    extra ones — and hands them to [TaskSeriesPlanner];
 *  - runs [TaskDomain.createInput] for validation (the single source of truth);
 *  - converts an [AppError.Validation] into the [Either.Left] side so callers can
 *    surface it as a UI message without inspecting an exception;
 *  - converts any persistence failure from [TaskRepository.createSeries] into
 *    [AppError.Persistence] so the caller doesn't have to inspect exceptions either.
 *
 * ## One date and several dates are the same path
 *
 * There is no branch here that says "if there are several dates, do something
 * else". A draft with one date produces one ordinary task with no series id — the
 * task it produced before this feature existed — and a draft with several produces
 * a series. The distinction is made once, in the planner.
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

        // The primary date keeps its relative form until this moment: a draft
        // restored the next morning still says "today", not "yesterday". The extra
        // dates are concrete, because there is no relative form for a day the user
        // picked in a grid.
        val primaryDate = resolveDate(draft.dueDate)
        val dates = (listOfNotNull(primaryDate) + draft.extraDueDates).distinct().sorted()

        if (dates.size > TaskSeriesPlanner.MAX_DATES) {
            return Either.Left(
                AppError.Validation(
                    message = "A series may hold at most ${TaskSeriesPlanner.MAX_DATES} dates",
                    code = "task.series.too_many_dates",
                ),
            )
        }

        val validated: Either<AppError.Validation, CreateTaskInput> = TaskDomain.createInput(
            title = draft.title.text,
            description = draft.description.text.ifBlank { null },
            priority = draft.priority,
            kind = TaskKind.Task,
            projectId = draft.projectId?.let { ProjectId.fromString(it) },
            parentTaskId = null,
            tagIds = draft.tagIds.map { TagId.fromString(it) },
            dueDate = primaryDate,
            dueTime = draft.dueTime,
            startDate = resolveDate(draft.startDate),
            startTime = draft.startTime,
            endDate = resolveDate(draft.endDate),
            endTime = draft.endTime,
            accentColor = draft.accentColor,
            emoji = draft.emoji,
            someday = false,
            recurrence = draft.recurrence,
        )
        return when (validated) {
            is Either.Left -> Either.Left(validated.error)

            is Either.Right -> {
                val userId = currentUser.scopedUserId.value
                val now = clock.now()
                // One id for the whole series — but only when there is a series.
                // A single date produces an ordinary task that carries no id, so
                // nothing downstream has to special-case it.
                val seriesId = if (dates.size > 1) TaskSeriesId.generate() else null
                val tasks = TaskSeriesPlanner.plan(
                    base = validated.value,
                    dates = dates,
                    seriesId = seriesId,
                    now = now,
                    userId = userId,
                )
                if (tasks.isEmpty()) {
                    // The planner never returns an empty list: a draft without
                    // dates is one undated task. Kept as a guard so a future
                    // planner change cannot turn "no dates" into "no task".
                    Either.Left(
                        AppError.Validation(
                            message = "A task needs at least one row",
                            code = "task.draft.no_rows",
                        ),
                    )
                } else {
                    repo.createSeries(tasks).fold(
                        onSuccess = { created ->
                            // The checklist and the attachments belong to the work,
                            // not to one of its occurrences: every occurrence gets
                            // its own copy, and the copies are independent from then on.
                            created.forEachIndexed { index, task ->
                                persistAttachmentsAndChecklist(draft, task.id, keepDraftIds = index == 0)
                            }
                            Either.Right(created.first().id)
                        },
                        onFailure = { e -> Either.Left(persistenceFailure(e)) },
                    )
                }
            }
        }
    }

    /**
     * Copies the draft's checklist and URL attachments onto one occurrence.
     *
     * @param keepDraftIds `true` for the first occurrence, which keeps the ids the
     *   draft already carried — a single-date save then produces exactly the rows
     *   it produced before. The other occurrences need fresh ids, because a
     *   checklist item's id is its primary key: the same id on two tasks would be
     *   one item, and ticking one would tick the other.
     */
    private suspend fun persistAttachmentsAndChecklist(
        draft: TaskDraft,
        taskId: TaskId,
        keepDraftIds: Boolean,
    ) {
        if (draft.checklist.isNotEmpty()) {
            val items = draft.checklist.map { draftItem ->
                ChecklistItem(
                    id = if (keepDraftIds) {
                        ChecklistItemId.fromString(draftItem.id)
                    } else {
                        ChecklistItemId.fromString(com.singularity.todo.core.ids.nextId())
                    },
                    taskId = taskId.value,
                    title = draftItem.text,
                    isCompleted = draftItem.isChecked,
                )
            }
            checklistRepository.createBatch(taskId.value, items)
        }
        for (draftAtt in draft.attachments) {
            attachmentRepository.addUrlAttachment(taskId, draftAtt.url, draftAtt.title)
        }
    }

    /**
     * The cause travels with the error. `AppError.Unknown`'s own KDoc says every
     * producer attaches one, and dropping it here is what made CreateTaskFlowTest's
     * save failure undiagnosable: the screen showed a generic message, the failure
     * bundle had no stack, and the cause was a lateinit that naming alone did not locate.
     */
    private fun persistenceFailure(e: Throwable): AppError.Persistence = AppError.Persistence(
        message = e.toMessage(),
        code = "task.draft.persist_failed",
        cause = e,
    )
}
