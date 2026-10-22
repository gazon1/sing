package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
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
 * ## The refusal
 *
 * Closing an occurrence is closing a task, and the rule is the same one every
 * other close obeys: the write refuses while a prerequisite is open, and the
 * caller asks the user. The refusal is in the write — the guarded statement —
 * and not in a check that runs before it, because a check-then-write has a
 * window between the two, and the window is where another device reopens the
 * prerequisite.
 *
 * A roll-forward is several writes — the completion, the copies of the missed
 * occurrences, the move to the next date — and they are one transaction, so a
 * refusal leaves nothing behind: no completion, no copies, no new date. A
 * refusal that left the copies behind would be refused twice on the retry.
 *
 * @param repo Task repository for create/update operations.
 * @param unitOfWork The transaction the roll-forward is one write inside.
 * @param clock Injected clock for determining current time.
 * @param timeZoneProvider Injected timezone for date calculations.
 * @param calculator Pure recurrence calculator.
 */
open class CompleteRecurringTaskUseCase(
    private val repo: TaskRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val calculator: RecurrenceCalculator,
) {
    /**
     * Completes the recurring task identified by [taskId], rolling it forward.
     *
     * @param force close the occurrence even while a prerequisite is open. The
     *   caller passes `true` only after the user has confirmed.
     * @return the newly updated (rolled-forward) task, or `null` if the task was not found
     * or had no recurrence rule.
     */
    open suspend operator fun invoke(taskId: TaskId, force: Boolean = false): Result<Task> {
        val task = repo.get(taskId) ?: return Result.failure(IllegalArgumentException("Task not found: $taskId"))
        val spec = task.recurrence ?: return Result.failure(IllegalArgumentException("Task has no recurrence: $taskId"))
        return invoke(task, spec, force)
    }

    private suspend fun invoke(task: Task, spec: RecurrenceSpec, force: Boolean): Result<Task> {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        val today = now.toLocalDateTime(zone).date

        return when (spec.base) {
            RecurrenceSpec.RecurrenceBase.FROM_DUE ->
                // Anchor: task's due date (or today if no due date)
                rollForward(task, spec, calculator.nextOccurrence(spec, task.dueDate ?: today), now, completeIt = true, force = force)

            RecurrenceSpec.RecurrenceBase.FROM_COMPLETION -> {
                // Anchor: completion timestamp
                val completedDate = task.completedAt?.toLocalDateTime(zone)?.date ?: today
                rollForward(task, spec, calculator.nextOccurrence(spec, completedDate), now, completeIt = false, force = force)
            }

            RecurrenceSpec.RecurrenceBase.CATCH_UP -> {
                val anchor = task.dueDate ?: today
                val missed = calculator.missedCount(spec, anchor, today)
                    .coerceIn(0, RecurrenceSpec.MAX_MISSED)

                // No missed occurrences — just roll forward normally
                if (missed == 0) {
                    return rollForward(task, spec, calculator.nextOccurrence(spec, anchor), now, completeIt = true, force = force)
                }

                // Generate N-1 historical copies for the missed occurrences (oldest first),
                // then roll the current task to the next future occurrence.
                // We cap at MAX_MISSED so we generate at most MAX_MISSED - 1 new copies
                // (the rolling task itself accounts for one slot).
                val copiesToGenerate = (missed - 1).coerceAtLeast(0)
                var currentAnchor = anchor
                val missedAnchors = mutableListOf<LocalDate>()

                repeat(copiesToGenerate) {
                    missedAnchors += currentAnchor
                    currentAnchor = calculator.nextOccurrence(spec, currentAnchor)
                }

                // The copies are written by the roll-forward, inside the same
                // transaction and after the guarded completion — so a refusal
                // leaves no copies behind, and the retry after the user's
                // confirmation does not write them twice.
                return rollForward(
                    task = task,
                    spec = spec,
                    nextDue = calculator.nextOccurrence(spec, currentAnchor),
                    now = now,
                    completeIt = true,
                    force = force,
                    missedAnchors = missedAnchors,
                )
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
     * branch re-implements it.
     *
     * @param completeIt `true` stamps this occurrence complete and rolls the due
     *        date; `false` un-completes the task and moves it forward (FROM_COMPLETION).
     * @param missedAnchors the occurrences that already happened — written as
     *        copies of the series, dated in the past.
     */
    private suspend fun rollForward(
        task: Task,
        spec: RecurrenceSpec,
        nextDue: LocalDate,
        now: Instant,
        completeIt: Boolean,
        force: Boolean,
        missedAnchors: List<LocalDate> = emptyList(),
    ): Result<Task> {
        val nowMillis = now.toEpochMilliseconds()
        if (spec.isExhaustedBy(nextDue)) {
            // The series ends here, but the missed occurrences already
            // happened: their copies are written all the same. The task is
            // archived rather than closed, so nothing about it is gated.
            return runCatchingCancellable {
                unitOfWork.write {
                    writeMissedCopies(task, missedAnchors, now)
                    terminate(task).getOrThrow()
                }
            }
        }
        return runCatchingCancellable {
            unitOfWork.write {
                if (completeIt) {
                    // The guarded statement: it refuses while a prerequisite is
                    // open, and the refusal is the write.
                    val closed = repo.completeOccurrence(task.id, nowMillis, nowMillis, force).getOrThrow()
                    if (!closed) throw BlockedTasksException(setOf(task.id))
                } else {
                    // The FROM_COMPLETION base rolls the open row forward instead
                    // of closing it. Nothing is closed, so nothing is gated.
                    repo.reopen(task.id).getOrThrow()
                }
                writeMissedCopies(task, missedAnchors, now)
                repo.moveDueDate(task.id, nextDue, nowMillis).getOrThrow()
                task.copy(
                    completedAt = if (completeIt) now else null,
                    dueDate = nextDue,
                    updatedAt = now,
                )
            }
        }
    }

    /**
     * Writes the copies of the occurrences that already happened.
     *
     * They are dated in the past and closed, and they are part of the same
     * transaction as the roll-forward: a refusal writes none of them, so a
     * retry after the user's confirmation does not write them twice.
     */
    private suspend fun writeMissedCopies(task: Task, missedAnchors: List<LocalDate>, now: Instant) {
        missedAnchors.forEach { anchor ->
            val historicalTask = task.copy(
                id = TaskId.generate(),
                completedAt = now,
                dueDate = anchor,
                updatedAt = now,
            )
            repo.create(historicalTask).getOrThrow()
        }
    }
}
