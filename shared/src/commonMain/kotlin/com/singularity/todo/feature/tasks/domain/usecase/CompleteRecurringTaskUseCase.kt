package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.isExhaustedBy
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Handles completion of a recurring task, rolling forward to the next occurrence.
 *
 * - **FROM_DUE**: next occurrence = `calculator.nextOccurrence(spec, task.dueDate ?? today)`
 * - **FROM_COMPLETION**: next occurrence = `calculator.nextOccurrence(spec, completedAt.toLocalDate())`
 * - **CATCH_UP**: generates up to [RecurrenceSpec.MAX_MISSED] copies for missed occurrences,
 *   then rolls the current task forward.
 *
 * @param repo Task repository for create/update operations.
 * @param clock Injected clock for determining current time.
 * @param timeZoneProvider Injected timezone for date calculations.
 * @param calculator Pure recurrence calculator.
 */
open class CompleteRecurringTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val calculator: RecurrenceCalculator,
) {
    /**
     * Completes the recurring task identified by [taskId], rolling it forward.
     *
     * Returns the newly updated (rolled-forward) task, or `null` if the task was not found
     * or had no recurrence rule.
     */
    open suspend operator fun invoke(taskId: TaskId): Result<Task> {
        val task = repo.get(taskId) ?: return Result.failure(IllegalArgumentException("Task not found: $taskId"))
        val spec = task.recurrence ?: return Result.failure(IllegalArgumentException("Task has no recurrence: $taskId"))
        return invoke(task, spec)
    }

    private suspend fun invoke(task: Task, spec: RecurrenceSpec): Result<Task> {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        val today = now.toLocalDateTime(zone).date

        return when (spec.base) {
            RecurrenceSpec.RecurrenceBase.FROM_DUE ->
                // Anchor: task's due date (or today if no due date)
                rollForward(task, spec, calculator.nextOccurrence(spec, task.dueDate ?: today), now, completeIt = true)

            RecurrenceSpec.RecurrenceBase.FROM_COMPLETION -> {
                // Anchor: completion timestamp
                val completedDate = task.completedAt?.toLocalDateTime(zone)?.date ?: today
                rollForward(task, spec, calculator.nextOccurrence(spec, completedDate), now, completeIt = false)
            }

            RecurrenceSpec.RecurrenceBase.CATCH_UP -> {
                val anchor = task.dueDate ?: today
                val missed = calculator.missedCount(spec, anchor, today)
                    .coerceIn(0, RecurrenceSpec.MAX_MISSED)

                // No missed occurrences — just roll forward normally
                if (missed == 0) {
                    return rollForward(task, spec, calculator.nextOccurrence(spec, anchor), now, completeIt = true)
                }

                // Generate N-1 historical copies for the missed occurrences (oldest first),
                // then roll the current task to the next future occurrence.
                // We cap at MAX_MISSED so we generate at most MAX_MISSED - 1 new copies
                // (the rolling task itself accounts for one slot).
                val copiesToGenerate = (missed - 1).coerceAtLeast(0)
                var currentAnchor = anchor

                repeat(copiesToGenerate) {
                    val nextOccurrence = calculator.nextOccurrence(spec, currentAnchor)
                    val historicalTask = task.copy(
                        id = TaskId.generate(),
                        completedAt = now,
                        dueDate = currentAnchor,
                        updatedAt = now,
                    )
                    val created = repo.create(historicalTask)
                    if (created.isFailure) return created
                    currentAnchor = nextOccurrence
                }

                // Roll the current task forward to the next occurrence after the last missed one.
                // The guard sits here, after the historical copies — those already
                // happened and are correct; only the roll-forward is suppressed.
                return rollForward(task, spec, calculator.nextOccurrence(spec, currentAnchor), now, completeIt = true)
            }
        }
    }

    /**
     * Ends a series whose next occurrence falls past its end date.
     *
     * Soft-deletes rather than inventing an archive call: `Task.isTrashed` is
     * `archivedAt != null`, and the app's own "Archive" action in
     * `TaskLifecycleSlot` is a `softDelete`. Reusing it means a finished series is
     * restorable from the trash exactly like a hand-archived task.
     *
     * A failure here returns the failure rather than swallowing it: in the
     * catch-up path the historical copies already exist, so a silent success
     * would leave a retry to duplicate them.
     */
    private suspend fun terminate(task: Task): Result<Task> = repo.softDelete(task.id).map { task }

    /**
     * Moves [task] to its next occurrence on [nextDue], or ends the series when
     * that occurrence falls past its end date.
     *
     * The one place the termination check lives, so no [RecurrenceSpec.RecurrenceBase]
     * branch re-implements it. A `CATCH_UP` caller runs this *after* its historical
     * copies are written — those already happened and stay.
     *
     * @param completeIt `true` stamps this occurrence complete and rolls the due
     *        date; `false` un-completes the task and moves it forward (FROM_COMPLETION).
     */
    private suspend fun rollForward(
        task: Task,
        spec: RecurrenceSpec,
        nextDue: LocalDate,
        now: Instant,
        completeIt: Boolean,
    ): Result<Task> {
        if (spec.isExhaustedBy(nextDue)) return terminate(task)
        val updated = task.copy(
            completedAt = if (completeIt) now else null,
            dueDate = nextDue,
            updatedAt = now,
        )
        return repo.update(updated).map { updated }
    }
}
