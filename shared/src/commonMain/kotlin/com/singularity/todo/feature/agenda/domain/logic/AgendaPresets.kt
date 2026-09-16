package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaLayout
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
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

    private fun section(name: String, selector: Selector, order: Int = 0, discard: Boolean = false) = Section(
        name = name,
        order = order,
        selector = selector,
        discard = discard,
    )

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
    fun byTag(id: TagId): AgendaDefinition = agenda("Tagged") {
        section("Tag", Selector.Tag(id), order = 0)
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

// ─── DSL helpers ─────────────────────────────────────────────────────────────

private fun agenda(title: String, block: AgendaScope.() -> Unit): AgendaDefinition {
    val scope = AgendaScope().apply(block)
    return AgendaDefinition(
        title = title,
        sections = scope.sections,
        layout = AgendaLayout.ListFlat,
    )
}

private class AgendaScope {
    internal val sections = mutableListOf<Section>()

    fun section(name: String, selector: Selector, order: Int, discard: Boolean = false) {
        sections.add(
            Section(
                name = name,
                order = order,
                selector = selector,
                discard = discard,
            ),
        )
    }
}
