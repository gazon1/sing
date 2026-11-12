package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.SectionPrefill
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Splits one section's rows into one section per period.
 *
 * This is the timeline behaviour the Upcoming tab keeps from the retired Plans
 * screen — and the reason it is a function on the evaluated rows rather than a
 * second timeline engine: the rows already carry their markings, project and
 * selection identity, so the agenda's screen renders them like any other
 * section.
 *
 * Only periods that carry a task become sections, and they are sorted
 * chronologically. That is the difference between a timeline and a calendar:
 * the 12th followed by the 19th is two headers and no six empty days in between,
 * and a week or a month grouping is the same rule at a coarser step. There is no
 * period count: a task due in three years still has its period. A row without a
 * due date cannot be dated, so it keeps the section's own name and sits last
 * rather than disappearing — the grouping is a rendering decision, not a filter.
 */
object DateSectionGrouper {
    /**
     * The sections [rows] of [section] fall into as of [today].
     *
     * Each generated section gets an id derived from the section's own id, the
     * grouping and the period's first day — `future:week:2026-11-09` — so a row is
     * keyed uniquely even when two grouped sections would otherwise share a
     * heading.
     */
    fun group(
        section: Section,
        rows: List<AgendaRowItem>,
        today: LocalDate,
        grouping: DateGrouping,
    ): List<RenderedSection> {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val dated =
            rows.mapNotNull { row ->
                row.task.dueDate?.let { date -> startOf(date, grouping) to row }
            }
        val undated = rows.filter { it.task.dueDate == null }
        val sections =
            dated
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .map { (start, items) -> section(section, grouping, start, items, today, tomorrow) }
        if (undated.isEmpty()) return sections

        val id = "${section.effectiveId}:undated"
        return sections +
            RenderedSection(
                id = id,
                name = section.name,
                tasks = undated.toImmutableList(),
                badge = undated.size,
                prefill = section.prefill,
            )
    }

    private fun section(
        section: Section,
        grouping: DateGrouping,
        start: LocalDate,
        rows: List<AgendaRowItem>,
        today: LocalDate,
        tomorrow: LocalDate,
    ): RenderedSection {
        val end = endOf(start, grouping)
        val id = "${section.effectiveId}:${grouping.name.lowercase()}:$start"
        return RenderedSection(
            id = id,
            name =
                when (grouping) {
                    DateGrouping.Day -> AgendaGroupLabels.day(start, today)
                    DateGrouping.Week -> AgendaGroupLabels.week(start, end, today)
                    DateGrouping.Month -> AgendaGroupLabels.month(start)
                },
            tasks = rows.toImmutableList(),
            badge = rows.size,
            // The '+' of a period creates a task due in that period. The date is
            // absolute because the section is generated for this evaluation — it
            // does not outlive the day it was built for — and it is never in the
            // past: the first day of the current week or month is today or earlier,
            // and the Today tab owns today.
            prefill = SectionPrefill(sectionId = id, dueDate = if (start > tomorrow) start else tomorrow),
        )
    }

    private fun startOf(
        date: LocalDate,
        grouping: DateGrouping,
    ): LocalDate =
        when (grouping) {
            DateGrouping.Day -> {
                date
            }

            DateGrouping.Week -> {
                date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)
            }

            DateGrouping.Month -> {
                LocalDate(date.year, date.month, 1)
            }
        }

    private fun endOf(
        start: LocalDate,
        grouping: DateGrouping,
    ): LocalDate =
        when (grouping) {
            DateGrouping.Day -> {
                start
            }

            DateGrouping.Week -> {
                start.plus(6, DateTimeUnit.DAY)
            }

            DateGrouping.Month -> {
                start.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            }
        }
}
