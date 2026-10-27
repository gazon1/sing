package com.singularity.todo.feature.plans.domain.logic

import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.model.TimelineSection
import com.singularity.todo.feature.plans.domain.model.TimelineTask
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.logic.computeTaskMarkings
import com.singularity.todo.feature.tasks.domain.logic.seriesSizes
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate

/**
 * Groups the open work into the sections the timeline shows.
 *
 * Pure: tasks, a granularity and a date in, sections out. The wall clock is
 * never read here.
 *
 * ## Why a task lands in exactly one section
 *
 * The periods of a granularity are pairwise disjoint, so a due date falls
 * inside at most one of them. There is no discard flag to get wrong, because
 * there is nothing to discard — the property is structural rather than a rule
 * someone has to remember.
 *
 * ## What is left out, and why
 *
 * - A **completed** task is out: the timeline shows the work that is still
 *   ahead, and a finished task has left the plan.
 * - A task **without a due date** is out: it has no period to belong to.
 * - A task due **beyond the horizon** is out: no period contains it.
 * - A period with **no tasks** is not a section: an empty day is not a heading.
 */
object TimelineGrouper {

    /** The id of the section that holds the work that is already late. */
    const val OVERDUE_ID: String = "overdue"

    /**
     * The sections [tasks] fall into, at [granularity], as of [today].
     *
     * The overdue section comes first when there is one; the dated sections
     * follow in chronological order.
     *
     * @param tasks Every task of the current user. The whole list, not the
     *   horizon's slice: a prerequisite outside the loaded set does not block
     *   a task, so a narrowed list would show a task as unblocked that the
     *   agenda shows as blocked.
     */
    fun group(
        tasks: List<Task>,
        granularity: PlansGranularity,
        today: LocalDate,
    ): List<TimelineSection> {
        val blockedIds = Blocking.blockedTaskIds(tasks)
        val seriesCount = seriesSizes(tasks)

        val dated = tasks.filter { !it.isCompleted && it.dueDate != null }
        val overdue = dated.filter { it.dueDate!! < today }
        val upcoming = dated.filter { it.dueDate!! >= today }

        val sections = mutableListOf<TimelineSection>()

        if (overdue.isNotEmpty()) {
            sections += TimelineSection(
                id = OVERDUE_ID,
                label = TimelineLabels.OVERDUE,
                period = null,
                tasks = overdue.map { it.toRow(today, blockedIds, seriesCount) }.toImmutableList(),
            )
        }

        for (period in TimelinePeriods.forGranularity(granularity, today)) {
            val inPeriod = upcoming.filter { period.contains(it.dueDate!!) }
            if (inPeriod.isEmpty()) continue
            sections += TimelineSection(
                id = period.id,
                label = TimelineLabels.forPeriod(granularity, period, today),
                period = period,
                // The date a task added from this section carries. It has to be
                // inside the period, or the task lands in another section than
                // the one it was added from — and it must not be in the past,
                // or the task is born overdue. So: today when the period holds
                // it, and the period's first day otherwise.
                prefillDate = if (period.contains(today)) today else period.start,
                tasks = inPeriod.map { it.toRow(today, blockedIds, seriesCount) }.toImmutableList(),
            )
        }

        return sections
    }

    private fun Task.toRow(
        today: LocalDate,
        blockedIds: Set<TaskId>,
        seriesCount: Map<String, Int>,
    ): TimelineTask = TimelineTask(
        task = this,
        markings = computeTaskMarkings(this, today, isBlocked = id in blockedIds),
        seriesDates = seriesId?.let { seriesCount[it.value] },
    )
}
