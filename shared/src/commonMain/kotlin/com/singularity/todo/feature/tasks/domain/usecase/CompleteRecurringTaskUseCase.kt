package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
            RecurrenceSpec.RecurrenceBase.FROM_DUE -> {
                // Anchor: task's due date (or today if no due date)
                val anchor = task.dueDate ?: today
                val nextDue = calculator.nextOccurrence(spec, anchor)
                val updated = task.copy(
                    completedAt = now,
                    dueDate = nextDue,
                    updatedAt = now,
                )
                repo.update(updated).map { updated }
            }

            RecurrenceSpec.RecurrenceBase.FROM_COMPLETION -> {
                // Anchor: completion timestamp
                val completedDate = task.completedAt?.toLocalDateTime(zone)?.date ?: today
                val nextDue = calculator.nextOccurrence(spec, completedDate)
                val updated = task.copy(
                    completedAt = null, // un-complete the task, move to next due date
                    dueDate = nextDue,
                    updatedAt = now,
                )
                repo.update(updated).map { updated }
            }

            RecurrenceSpec.RecurrenceBase.CATCH_UP -> {
                val anchor = task.dueDate ?: today
                val missed = calculator.missedCount(spec, anchor, today)
                    .coerceIn(0, RecurrenceSpec.MAX_MISSED)

                if (missed == 0) {
                    // No missed occurrences — just roll forward normally
                    val nextDue = calculator.nextOccurrence(spec, anchor)
                    val updated = task.copy(
                        completedAt = now,
                        dueDate = nextDue,
                        updatedAt = now,
                    )
                    return repo.update(updated).map { updated }
                }

                // Generate N-1 historical copies for the missed occurrences (oldest first),
                // then roll the current task to the next future occurrence.
                // We cap at MAX_MISSED so we generate at most MAX_MISSED - 1 new copies
                // (the rolling task itself accounts for one slot).
                val copiesToGenerate = (missed - 1).coerceAtLeast(0)
                val createdTasks = mutableListOf<Task>()
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
                    createdTasks.add(created.getOrThrow())
                    currentAnchor = nextOccurrence
                }

                // Roll the current task forward to the next occurrence after the last missed one
                val nextDue = calculator.nextOccurrence(spec, currentAnchor)
                val updated = task.copy(
                    completedAt = now,
                    dueDate = nextDue,
                    updatedAt = now,
                )
                val finalResult = repo.update(updated)
                if (finalResult.isFailure) return finalResult

                // Return the rolled-forward task (not the historical copies)
                Result.success(finalResult.getOrThrow())
            }
        }
    }
}
