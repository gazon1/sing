package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Turns a task that stands alone into a recurring series — the task screen's door.
 *
 * Setting a rule on a task is the second of the two doors a rule reaches a row
 * through; the first is the create screen. Both have to materialise the series, so
 * both go through [MaterializeRecurringSeriesUseCase]; this use case is the thin
 * one that reads the task, decides it stands alone, and hands the work over.
 *
 * Setting a rule on a task that is **already in a series** is a different write —
 * the whole series changes its rule — and it goes through
 * [UpdateTaskSeriesUseCase], not here.
 */
class StartRecurringSeriesUseCase(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val timeZone: TimeZoneProvider,
    private val materialize: MaterializeRecurringSeriesUseCase,
) {
    /**
     * @param taskId the task to turn into a series.
     * @param rule the rule to set on it.
     * @return the series id, or a failure. A task that is already in a series is a
     *   failure here — its rule change is the series' business, not this door's.
     */
    suspend operator fun invoke(taskId: TaskId, rule: RecurrenceSpec): Result<TaskSeriesId> =
        runCatchingCancellable {
            val task = repo.get(taskId)
                ?: throw IllegalStateException("Task $taskId not found")
            if (task.seriesId != null) {
                throw IllegalStateException("Task $taskId is already in a series: ${task.seriesId}")
            }
            val today = clock.now().toLocalDateTime(timeZone.current()).date
            materialize.startSeries(task = task, rule = rule, today = today).getOrThrow()
        }
}
