package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Closes one occurrence of a recurring task and materialises the next one.
 *
 * ## The two things every base does
 *
 * Every base closes the occurrence and materialises the next occurrence as a **new
 * row**. The occurrence stays on its own date; its date never moves. The bases
 * differ only in *which date* the rule is measured from:
 *
 * | Base | The anchor the next occurrences are measured from |
 * |---|---|
 * | [RecurrenceSpec.RecurrenceBase.FROM_DUE] | the occurrence's due date |
 * | [RecurrenceSpec.RecurrenceBase.FROM_COMPLETION] | the completion date |
 * | [RecurrenceSpec.RecurrenceBase.CATCH_UP] | the occurrence's due date, with the missed occurrences materialised as closed rows first |
 *
 * ## Why the date no longer moves
 *
 * The old roll-forward closed the row and then moved its date, and the move never
 * touched `completed_at` — so the row was closed *and* dated in the future. It left
 * "Today" and came back in "Upcoming" struck through, and the next occurrence never
 * existed as an open task. Closing the occurrence and creating the next one as a
 * separate row removes that defect entirely: the row's date never moves.
 *
 * ## The refusal
 *
 * Closing is closing, and the write refuses while a prerequisite is open. The
 * refusal is the guarded statement in [TaskRepository.completeOccurrence] — not a
 * check that runs before it — and it is the *first* write, so a refusal leaves
 * nothing behind: no completion, no new occurrence, no missed copies. The whole
 * close is one transaction.
 *
 * ## A rule that has run out
 *
 * When the rule has run out — there is no next date — the occurrence is **closed**
 * and the series stops growing. Nothing is archived: a closed occurrence is already
 * out of the active list, and archiving a row the user closed would move finished
 * work into the trash.
 *
 * @param repo Task repository.
 * @param unitOfWork The transaction the close and the materialisation are one write inside.
 * @param clock Injected clock.
 * @param timeZoneProvider Injected timezone for date calculations.
 * @param calculator Pure recurrence calculator.
 * @param materialize Fills the window of the series after the close.
 */
open class CompleteRecurringTaskUseCase(
    private val repo: TaskRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val calculator: RecurrenceCalculator,
    private val materialize: MaterializeRecurringSeriesUseCase,
) {
    /**
     * Completes the recurring task identified by [taskId], closing the occurrence
     * and materialising the next occurrences of the series.
     *
     * @param force close the occurrence even while a prerequisite is open. The
     *   caller passes `true` only after the user has confirmed.
     * @return the closed occurrence, or a failure. A task with no rule is a
     *   failure — this path is only for a repeating task.
     */
    open suspend operator fun invoke(taskId: TaskId, force: Boolean = false): Result<Task> {
        val task = repo.get(taskId) ?: return Result.failure(IllegalArgumentException("Task not found: $taskId"))
        val spec = task.recurrence
            ?: return Result.failure(IllegalArgumentException("Task has no recurrence: $taskId"))
        return invoke(task, spec, force)
    }

    private suspend fun invoke(task: Task, spec: RecurrenceSpec, force: Boolean): Result<Task> {
        val now = clock.now()
        val zone = timeZoneProvider.current()
        val today = now.toLocalDateTime(zone).date

        return when (spec.base) {
            RecurrenceSpec.RecurrenceBase.FROM_DUE ->
                closeAndMaterialize(task, spec, anchor = task.dueDate ?: today, now = now, force = force)

            RecurrenceSpec.RecurrenceBase.FROM_COMPLETION ->
                closeAndMaterialize(task, spec, anchor = today, now = now, force = force)

            RecurrenceSpec.RecurrenceBase.CATCH_UP -> {
                // Catch-up fills the gaps between *scheduled* occurrences, so its
                // anchor is the due date. The missed occurrences become closed
                // rows dated in the past, in the same transaction as the close.
                val anchor = task.dueDate ?: today
                val missed = calculator.missedCount(spec, anchor, today)
                    .coerceIn(0, RecurrenceSpec.MAX_MISSED)
                val missedAnchors = generateMissedAnchors(spec, anchor, missed)
                closeAndMaterialize(
                    task = task,
                    spec = spec,
                    anchor = anchor,
                    now = now,
                    force = force,
                    missedAnchors = missedAnchors,
                )
            }
        }
    }

    /**
     * The dates of the occurrences that were scheduled and missed, oldest first.
     *
     * The rolling occurrence itself accounts for the most recent slot, so at most
     * `missed - 1` copies are produced — the same accounting the one-row model used.
     */
    private fun generateMissedAnchors(spec: RecurrenceSpec, anchor: LocalDate, missed: Int): List<LocalDate> {
        val copies = (missed - 1).coerceAtLeast(0)
        val dates = mutableListOf<LocalDate>()
        var current = anchor
        repeat(copies) {
            dates += current
            current = calculator.nextOccurrence(spec, current)
        }
        return dates
    }

    /**
     * The close: close the occurrence where it is, write the missed copies, and
     * materialise the series' window from [anchor] — all in one transaction, so a
     * failure anywhere leaves nothing behind and a refusal writes nothing at all.
     */
    private suspend fun closeAndMaterialize(
        task: Task,
        spec: RecurrenceSpec,
        anchor: LocalDate,
        now: Instant,
        force: Boolean,
        missedAnchors: List<LocalDate> = emptyList(),
    ): Result<Task> {
        val nowMillis = now.toEpochMilliseconds()
        return runCatchingCancellable {
            unitOfWork.write {
                // The guarded statement: it refuses while a prerequisite is open,
                // and the refusal is the write.
                val done = repo.completeOccurrence(task.id, nowMillis, nowMillis, force).getOrThrow()
                if (!done) throw BlockedTasksException(setOf(task.id))
                writeMissedCopies(task, missedAnchors, now)
                val closed = task.copy(completedAt = now, updatedAt = now)
                // The window is filled from the anchor the base decides, in the same
                // transaction: the materialisation nests into this one, so either
                // the whole close-and-fill lands or none of it does.
                materialize(template = closed, rule = spec, anchor = anchor).getOrThrow()
                closed
            }
        }
    }

    /**
     * Writes the copies of the occurrences that already happened.
     *
     * They are dated in the past and closed, and they are part of the same
     * transaction as the close: a refusal writes none of them, so a retry after the
     * user's confirmation does not write them twice.
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
