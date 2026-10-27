package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * What a write has to do to make a recurring series hold its window.
 *
 * A plan first and a write second, so the plan can be asserted on without a
 * database. The use case turns it into one transaction; the planner turns the
 * series' state into the plan.
 *
 * @param toCreate the new occurrences the series does not have yet.
 * @param toMove the rows that survive the re-plan with a new date — a "from
 *   completion" series shifts when a close is late. They keep their id and their
 *   own state (a reminder, a checklist); only the date moves.
 * @param toDetach the rows that leave the series: the open rows the new rule no
 *   longer produces, and — at the ceiling — the oldest closed row. A row that
 *   leaves a recurring series loses the rule with the series id.
 */
data class SeriesMaterialisationPlan(val toCreate: List<Task>, val toMove: List<Task>, val toDetach: List<Task>) {
    val isEmpty: Boolean get() = toCreate.isEmpty() && toMove.isEmpty() && toDetach.isEmpty()

    companion object {
        val empty = SeriesMaterialisationPlan(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Turns the state of a recurring series into the rows that carry its window.
 *
 * Pure: no I/O, no clock of its own — the caller supplies [now] and the ids, so a
 * plan is a value that can be asserted on.
 *
 * ## The two kinds of series, restated
 *
 * A **picked-dates** series has dates the user chose and no rule; it is planned by
 * [TaskSeriesPlanner] and this planner never sees it. A **recurring** series has the
 * rule's own occurrences as its dates and the rule on every member; this planner
 * produces it.
 *
 * ## What the plan reconciles
 *
 * The series holds two populations of rows: the **history** — the closed
 * occurrences, which are never rewritten — and the **window** — the open
 * occurrences, which are the rule's dates from the series' anchor. A re-plan makes
 * the window match the rule again:
 *
 * * a date the window gives and the series lacks becomes a new row ([toCreate]);
 * * an open row whose date the window still gives survives, moved if the date
 *   changed ([toMove]);
 * * an open row whose date the window no longer gives leaves the series
 *   ([toDetach]);
 * * the closed rows are left exactly as they are (FR-015).
 *
 * ## The ceiling
 *
 * The series holds at most [TaskSeriesPlanner.MAX_DATES] rows in total. When
 * adding the window would cross that, the **oldest closed** row leaves the series
 * ([toDetach]) — it stays an ordinary closed task, and it loses the rule with the
 * series id, or the launch repair would adopt it back and the ceiling would
 * become a loop.
 *
 * @see RecurringSeriesWindow for the dates.
 */
object RecurringSeriesPlanner {

    /**
     * The plan that makes [members] hold the window of [rule] from [anchor].
     *
     * @param template the row the new occurrences are built from — its shared
     *   fields are the work's, and its dates (start/end window) are shifted onto
     *   each new occurrence. It carries the section's [TaskSeriesId].
     * @param rule the rule every member carries.
     * @param anchor the date the window is applied from.
     * @param members the current rows of the series.
     * @param now the timestamp stamped on the rows this plan creates.
     * @param horizonDays forwarded to [RecurringSeriesWindow.plan].
     * @param maxFuture forwarded to [RecurringSeriesWindow.plan].
     * @return the plan.
     */
    fun plan(
        template: Task,
        rule: RecurrenceSpec,
        anchor: LocalDate,
        members: List<Task>,
        now: Instant,
        horizonDays: Int = RecurringSeriesWindow.HORIZON_DAYS,
        maxFuture: Int = RecurringSeriesWindow.MAX_FUTURE,
    ): SeriesMaterialisationPlan {
        val seriesId = template.seriesId
            ?: error("A recurring series is planned around a row that carries a series id")

        val window = RecurringSeriesWindow.plan(rule, anchor, horizonDays, maxFuture)
        val openRows = members.filter { it.completedAt == null && !it.isTrashed }
        val closedRows = members.filter { it.completedAt != null || it.isTrashed }

        // The current occurrence — an open row at or before the anchor — is kept in
        // place: the window looks strictly after the anchor, so it is not part of
        // what the window plans for. Only the open rows *ahead* of the anchor are
        // matched to the window.
        val aheadRows = openRows
            .filter { row -> (row.dueDate ?: anchor) > anchor }
            .sortedBy { it.dueDate }

        // Pair the open rows ahead of the anchor with the window dates positionally.
        // The pairing is by position, not by exact date, so a "from completion" close
        // that shifts the window moves the surviving rows' dates instead of rebuilding
        // them — the row keeps its id and its own state (a reminder, a checklist). An
        // open row beyond the window leaves the series; a window date beyond the rows
        // is created.
        val paired = minOf(aheadRows.size, window.size)
        val toMove = (0 until paired).mapNotNull { i ->
            val row = aheadRows[i]
            val date = window[i]
            if (row.dueDate == date) null else row.copy(dueDate = date, updatedAt = now)
        }
        val toDetach = aheadRows.drop(window.size).map { it.withRuleCleared() }
        val toCreate = window.drop(aheadRows.size)
            .map { date -> buildOccurrence(template, seriesId, date, now, rule) }

        // The ceiling: the series holds at most MAX_DATES rows. If creating the
        // window would cross it, the oldest closed rows rotate out — they are
        // detached and keep the rule off, exactly as a user detach does.
        val survivorsCount = members.size - toDetach.size + toCreate.size
        val rotated = mutableListOf<Task>()
        run {
            val history = closedRows.sortedBy { it.dueDate }.toMutableList()
            var over = survivorsCount - TaskSeriesPlanner.MAX_DATES
            while (over > 0 && history.isNotEmpty()) {
                rotated += history.removeAt(0).withRuleCleared()
                over--
            }
        }

        val allDetached = (toDetach + rotated).distinctBy { it.id }

        return SeriesMaterialisationPlan(
            toCreate = toCreate,
            toMove = toMove,
            toDetach = allDetached,
        )
    }

    /**
     * A row that leaves a recurring series carries neither the series id nor the
     * rule.
     *
     * A row that kept the rule would carry the repeat badge and never repeat — and
     * a rule on a row that stands alone is the exact shape the launch repair adopts
     * into a new series, so the detach (or the ceiling) would undo itself at the
     * next launch.
     */
    private fun Task.withRuleCleared(): Task = copy(seriesId = null, recurrence = null)

    /** Builds one occurrence of the series, dated [date]. */
    private fun buildOccurrence(
        template: Task,
        seriesId: TaskSeriesId,
        date: LocalDate,
        now: Instant,
        rule: RecurrenceSpec,
    ): Task {
        val primary = template.dueDate ?: date
        val delta = (date.toEpochDays() - primary.toEpochDays()).toInt()
        return TaskDomain.buildTask(
            input = com.singularity.todo.feature.tasks.domain.model.CreateTaskInput(
                title = template.title,
                description = template.description,
                priority = template.priority,
                kind = template.kind,
                projectId = template.projectId,
                parentTaskId = template.parentTaskId,
                tagIds = template.tags,
                dueDate = date,
                dueTime = template.dueTime,
                startDate = template.startDate?.shiftedBy(delta),
                startTime = template.startTime,
                endDate = template.endDate?.shiftedBy(delta),
                endTime = template.endTime,
                accentColor = template.accentColor,
                emoji = template.emoji,
                estimateMinutes = template.estimateMinutes,
                someday = false,
                recurrence = rule,
            ),
            id = TaskId.generate(),
            createdAt = now,
            updatedAt = now,
            userId = template.userId,
        ).copy(seriesId = seriesId)
    }

    private fun LocalDate.shiftedBy(days: Int): LocalDate =
        if (days == 0) this else plus(days, kotlinx.datetime.DateTimeUnit.DAY)
}
