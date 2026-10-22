package com.singularity.todo.feature.tasks.domain.model

import kotlinx.serialization.Serializable

/**
 * The identity of a *series*: one piece of work scheduled on several dates.
 *
 * ## What a series is
 *
 * A series is N ordinary task rows that share one [TaskSeriesId]. Each row has its
 * own date, its own completion state, its own checklist, its own attachments and its
 * own reminders. What they share is the work itself — the title, the project, the
 * tags, the priority, the estimate — and those are edited once for the whole series.
 *
 * ## Why rows and not a table of occurrences
 *
 * The alternative is one row with a list of dates, which needs an occurrences table
 * and a JOIN in every read path in the app: the agenda, the calendar, the search
 * index, the backup export, the Google Calendar push, the MCP and AI tools. This
 * codebase reads `Task.title` and `Task.dueDate` directly everywhere, so the series
 * is stored as materialized copies and every existing read path keeps working
 * unchanged.
 *
 * The cost of that choice is that the shared fields are stored N times and a series
 * edit writes N rows in one transaction. The divergence that can cause is bounded:
 * the next series edit rewrites every row, so it heals itself. See the ADR
 * `2026-10-22-task-series-materialized-copies`.
 *
 * @see Task.seriesId
 */
@Serializable
@JvmInline
value class TaskSeriesId(val value: String) {
    companion object {
        /** Mints a fresh series id. */
        fun generate(): TaskSeriesId = TaskSeriesId(com.singularity.todo.core.ids.nextId())

        /** Wraps an id that came from storage or from the wire. */
        fun fromString(value: String): TaskSeriesId = TaskSeriesId(value)
    }
}
