package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.tasks.domain.logic.RecurringSeriesPlanner
import com.singularity.todo.feature.tasks.domain.logic.SeriesMaterialisationPlan
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Fills the window of a recurring series.
 *
 * A rule reaches a row by two doors — the create screen and the task screen — and
 * both materialise the series through this use case, so the two doors cannot drift
 * apart in what they write.
 *
 * ## One transaction, and why the write is two calls
 *
 * The plan may create members **and** detach one (the ceiling rotates a row out, or
 * a re-plan drops an open date). The repository's API does not offer one call that
 * does both:
 *
 * * [TaskRepository.createSeries] upserts, so it can create and update — but it
 *   **refuses** a batch that carries two series ids, and a detached row
 *   (`seriesId = null`) beside a member (`seriesId = <id>`) is exactly such a
 *   batch;
 * * [TaskRepository.updateAll] updates only, and refuses to invent a member.
 *
 * So the write is [TaskRepository.createSeries] for the members and
 * [TaskRepository.update] for each detached row, inside one [UnitOfWork]. Either
 * order is safe: `repo.update` triggers the series realignment, but the row it is
 * handed is the *detached* one, whose series id is null, and the realignment
 * returns at once for such a row.
 */
class MaterializeRecurringSeriesUseCase(
    private val repo: TaskRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: Clock,
) {

    /**
     * Fills [seriesId]'s window from [anchor], and returns the plan that was
     * written.
     *
     * @param template the row the new occurrences are built from — its shared
     *   fields are the work's. It must carry [seriesId].
     * @param rule the rule every member carries.
     * @param anchor the date the window is applied from.
     * @return the plan written, or a failure. An empty plan writes nothing.
     */
    suspend operator fun invoke(
        template: Task,
        rule: RecurrenceSpec,
        anchor: LocalDate,
    ): Result<SeriesMaterialisationPlan> = runCatchingCancellable {
        val seriesId = template.seriesId
            ?: throw IllegalArgumentException("A recurring series is materialised around a row with a series id")
        val members = repo.getSeries(seriesId)
        val now = clock.now()
        val plan = RecurringSeriesPlanner.plan(
            template = template,
            rule = rule,
            anchor = anchor,
            members = members,
            now = now,
        )
        if (plan.isEmpty) return@runCatchingCancellable plan

        unitOfWork.write {
            val batch = plan.toCreate + plan.toMove
            if (batch.isNotEmpty()) {
                repo.createSeries(batch).getOrThrow()
            }
            // The rows that leave the series are written on their own: they carry
            // no series id, and a batch that mixed them with the members would be
            // the two-series-ids write `createSeries` refuses.
            plan.toDetach.forEach { detached ->
                repo.update(detached).getOrThrow()
            }
        }
        plan
    }

    /**
     * Creates a recurring series around a task that stands alone — the rule was
     * just set on it, and the task becomes the current occurrence.
     *
     * The anchor is the task's due date while it is still open — for every base,
     * including "from completion", because a completion date does not exist yet and
     * the due date is the best estimate available.
     *
     * @param task the row the rule was set on. It must carry the rule and no series.
     * @param rule the rule.
     * @param today used as the anchor only when the task carries no due date.
     * @return the series id, or a failure.
     */
    suspend fun startSeries(task: Task, rule: RecurrenceSpec, today: LocalDate): Result<TaskSeriesId> =
        runCatchingCancellable {
            val seriesId = TaskSeriesId.generate()
            val current = task.copy(seriesId = seriesId, recurrence = rule, updatedAt = clock.now())
            // The row and its window land together: the materialisation nests into
            // this transaction, so a failure leaves no series of one behind.
            unitOfWork.write {
                repo.update(current).getOrThrow()
                invoke(template = current, rule = rule, anchor = current.dueDate ?: today).getOrThrow()
            }
            seriesId
        }
}
