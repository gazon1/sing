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

    /** All active tasks, grouped by relative date bucket. */
    val Inbox: AgendaDefinition = agenda("Inbox") {
        section("Today", Selector.DateBucket(RelativeBucket.Today), order = 0)
        section("Yesterday", Selector.DateBucket(RelativeBucket.Yesterday), order = 1)
        section("Tomorrow", Selector.DateBucket(RelativeBucket.Tomorrow), order = 2)
        section("This Week", Selector.DateBucket(RelativeBucket.ThisWeek), order = 3)
        section("Next Week", Selector.DateBucket(RelativeBucket.NextWeek), order = 4)
        section("This Month", Selector.DateBucket(RelativeBucket.ThisMonth), order = 5)
        section("No Date", Selector.DateBucket(RelativeBucket.NoDate), order = 6)
        section("Overdue", Selector.DateBucket(RelativeBucket.Overdue), order = -1, discard = true)
    }

    /** Only today's tasks, with overdue shown at the top. */
    val Today: AgendaDefinition = agenda("Today") {
        section("Overdue", Selector.DateBucket(RelativeBucket.Overdue), order = 0)
        section("Today", Selector.DateBucket(RelativeBucket.Today), order = 1)
    }

    /** Upcoming tasks for the next 2 weeks, grouped by week. */
    val Upcoming: AgendaDefinition = agenda("Upcoming") {
        section("Overdue", Selector.DateBucket(RelativeBucket.Overdue), order = 0, discard = true)
        section("Today", Selector.DateBucket(RelativeBucket.Today), order = 1)
        section("Tomorrow", Selector.DateBucket(RelativeBucket.Tomorrow), order = 2)
        section("This Week", Selector.DateBucket(RelativeBucket.ThisWeek), order = 3)
        section("Next Week", Selector.DateBucket(RelativeBucket.NextWeek), order = 4)
    }

    /**
     * All tasks for a specific project.
     * @param id The [ProjectId] to filter by.
     */
    fun byProject(id: ProjectId): AgendaDefinition = agenda(id.value) {
        section("Project Tasks", Selector.Projects(setOf(id)), order = 0)
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
        section("Tags", Selector.Tags(ids), order = 0)
    }

    /**
     * All tasks within a date range (inclusive).
     * Used by the Calendar screen.
     *
     * @param from Start of the range (inclusive).
     * @param to End of the range (inclusive).
     */
    fun byDateRange(from: LocalDate, to: LocalDate): AgendaDefinition = agenda("Date Range") {
        section("Range", Selector.DateRange(from, to), order = 0)
    }
}

