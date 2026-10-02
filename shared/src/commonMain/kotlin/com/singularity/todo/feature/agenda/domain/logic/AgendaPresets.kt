package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.agenda
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate

/**
 * Built-in agenda presets.
 *
 * Each preset is a pure data [AgendaDefinition] with no external dependencies —
 * it can be serialised, cached, and recreated from JSON.
 *
 * Note: SQL-optimised [Section] filtering (via `baseFilter` on sections)
 * is deferred to MR2 after [com.singularity.todo.feature.tasks.domain.model.TaskFilter]
 * subclasses are annotated with `@SerialName`. In MR1 all filtering happens in-process.
 */
object AgendaPresets {

    /**
     * All active tasks, grouped by relative date bucket.
     *
     * Sections are ordered narrowest-bucket-first and each one `discard`s what it
     * matches, so a task lands in exactly one section: the most specific bucket that
     * contains it. Without that, a task due today also matches "This Week" and
     * "This Month" and is rendered three times — which crashed the agenda's
     * `LazyColumn` on duplicate item keys, since `AgendaList` keys rows by task id
     * alone. "This Month" is the final catch-all and deliberately does *not* discard;
     * "No Date" sits after it but is disjoint (`dueDate == null`).
     */
    val Inbox: AgendaDefinition = agenda("Inbox") {
        section("overdue", "Overdue", Selector.DateBucket(RelativeBucket.Overdue), order = -1, discard = true)
        section("today", "Today", Selector.DateBucket(RelativeBucket.Today), order = 0, discard = true)
        section("yesterday", "Yesterday", Selector.DateBucket(RelativeBucket.Yesterday), order = 1, discard = true)
        section("tomorrow", "Tomorrow", Selector.DateBucket(RelativeBucket.Tomorrow), order = 2, discard = true)
        section("this_week", "This Week", Selector.DateBucket(RelativeBucket.ThisWeek), order = 3, discard = true)
        section("next_week", "Next Week", Selector.DateBucket(RelativeBucket.NextWeek), order = 4, discard = true)
        section("this_month", "This Month", Selector.DateBucket(RelativeBucket.ThisMonth), order = 5)
        section("no_date", "No Date", Selector.DateBucket(RelativeBucket.NoDate), order = 6)
    }

    /** Only today's tasks, with overdue shown at the top. */
    val Today: AgendaDefinition = agenda("Today") {
        section("overdue", "Overdue", Selector.DateBucket(RelativeBucket.Overdue), order = 0, discard = true)
        section("today", "Today", Selector.DateBucket(RelativeBucket.Today), order = 1)
    }

    /**
     * Upcoming tasks for the next 2 weeks, grouped by week.
     *
     * "This Week" and "Next Week" are disjoint ranges, so the last of them needs no
     * `discard`; the narrower buckets above them do, for the same reason as [Inbox].
     */
    val Upcoming: AgendaDefinition = agenda("Upcoming") {
        section("overdue", "Overdue", Selector.DateBucket(RelativeBucket.Overdue), order = 0, discard = true)
        section("today", "Today", Selector.DateBucket(RelativeBucket.Today), order = 1, discard = true)
        section("tomorrow", "Tomorrow", Selector.DateBucket(RelativeBucket.Tomorrow), order = 2, discard = true)
        section("this_week", "This Week", Selector.DateBucket(RelativeBucket.ThisWeek), order = 3)
        section("next_week", "Next Week", Selector.DateBucket(RelativeBucket.NextWeek), order = 4)
    }

    /**
     * All tasks for a specific project.
     * @param id The [ProjectId] to filter by.
     */
    fun byProject(id: ProjectId): AgendaDefinition = agenda(id.value) {
        section("project_tasks", "Project Tasks", Selector.Projects(setOf(id)), order = 0)
    }

    /**
     * All tasks with a specific tag.
     * @param id The [TagId] to filter by.
     */
    fun byTag(id: TagId): AgendaDefinition = byTags(setOf(id))

    /**
     * All tasks tagged with any of the given [TagId]s.
     *
     * @param ids The set of tag IDs to filter by. A task matching any one of them is included.
     */
    fun byTags(ids: Set<TagId>): AgendaDefinition = agenda("Tagged") {
        section("tags", "Tags", Selector.Tags(ids), order = 0)
    }

    /**
     * All tasks within a date range (inclusive).
     * Used by the Calendar screen.
     *
     * @param from Start of the range (inclusive).
     * @param to End of the range (inclusive).
     */
    fun byDateRange(from: LocalDate, to: LocalDate): AgendaDefinition = agenda("Date Range") {
        section("date_range", "Range", Selector.DateRange(from, to), order = 0)
    }
}
