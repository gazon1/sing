package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * Turns one piece of work scheduled on several dates into the rows that carry it.
 *
 * Pure: no I/O, no clock, no repository. The caller supplies [now] and the ids it
 * wants, so the plan of a series is a value that can be asserted on.
 *
 * ## The rules
 *
 * * **One date is not a series.** A single date produces a single task with no
 *   [Task.seriesId] at all, so the ordinary one-day task keeps behaving exactly as
 *   it did before the feature existed.
 * * **Several dates share one id.** Every row of the series carries the same
 *   [TaskSeriesId]; that id is the only thing that says the rows belong together.
 * * **The active window travels with the date.** A task whose window starts three
 *   days before its date keeps that offset in every occurrence — otherwise the
 *   window would drift relative to the date it belongs to.
 * * **No recurrence inside a series.** "Every Monday" and "these five dates" are
 *   two different intentions, and a row that had both would generate occurrences
 *   that were never asked for.
 */
object TaskSeriesPlanner {

    /**
     * The hard ceiling on how many dates one series may hold.
     *
     * A ceiling of thirty is a refusal, not a suggestion: five hundred tasks from
     * one action is a way of loading the database, not a feature. The check lives
     * here, in the domain, so it holds for every entry point and not only for the
     * picker that happens to show the message.
     */
    const val MAX_DATES: Int = 30

    /**
     * Builds the rows of a series.
     *
     * @param base The shared fields of the work. Its [CreateTaskInput.dueDate] is
     *   ignored: the dates come from [dates].
     * @param dates The dates of the occurrences. Normalized here — sorted and
     *   de-duplicated — so a caller may pass them in any order. An empty list
     *   means "no date at all": one undated task, and not a series.
     * @param seriesId The id the rows share. `null` means "mint one"; a single-date
     *   plan carries no id at all.
     * @param now The creation timestamp stamped on every row.
     * @param userId The owner of every row.
     * @return the rows, in date order. Never empty: a plan without dates is one
     *   undated task.
     * @throws IllegalArgumentException if [dates] holds more than [MAX_DATES]
     *   distinct dates.
     */
    fun plan(
        base: CreateTaskInput,
        dates: List<LocalDate>,
        seriesId: TaskSeriesId? = null,
        now: Instant,
        userId: UserId,
    ): List<Task> {
        val normalized = dates.distinct().sorted()
        require(normalized.size <= MAX_DATES) {
            "A series may hold at most $MAX_DATES dates, got ${normalized.size}"
        }
        if (normalized.isEmpty()) {
            // No date: an ordinary undated task. The planner refuses to invent one.
            return listOf(
                TaskDomain.buildTask(
                    input = base.copy(dueDate = null, recurrence = null, someday = false),
                    id = TaskId.generate(),
                    createdAt = now,
                    updatedAt = now,
                    userId = userId,
                ),
            )
        }

        val primary = normalized.first()
        // A single date is an ordinary task, not a one-member series: it carries
        // no series id, so nothing downstream has to special-case it.
        val effectiveSeriesId = if (normalized.size == 1) null else (seriesId ?: TaskSeriesId.generate())

        return normalized.map { date ->
            // kotlinx-datetime 0.8's `LocalDate - LocalDate` is a DatePeriod, not a
            // number of days, so the offset is taken off the epoch-day count.
            val delta = (date.toEpochDays() - primary.toEpochDays()).toInt()
            val input = base.copy(
                dueDate = date,
                startDate = base.startDate?.shiftByDays(delta),
                endDate = base.endDate?.shiftByDays(delta),
                // A row with a rule would generate occurrences of its own; inside a
                // series that would be occurrences nobody asked for.
                recurrence = null,
                // A dated row is not a someday row.
                someday = false,
            )
            TaskDomain.buildTask(
                input = input,
                id = TaskId.generate(),
                createdAt = now,
                updatedAt = now,
                userId = userId,
            ).copy(seriesId = effectiveSeriesId)
        }
    }

    /** Moves a date by [days] — positive forward, negative back. */
    private fun LocalDate.shiftByDays(days: Int): LocalDate =
        if (days == 0) this else plus(days, DateTimeUnit.DAY)
}
