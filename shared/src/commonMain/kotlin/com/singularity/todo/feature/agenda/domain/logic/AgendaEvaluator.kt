package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.logic.computeTaskMarkings
import com.singularity.todo.feature.tasks.domain.logic.seriesSizes
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Pure agenda evaluation engine.
 *
 * Takes an [AgendaDefinition] and a list of [Task]s and produces a list of
 * [RenderedSection]s ready for display.
 *
 * ## Algorithm
 *
 * 1. Sort sections by [Section.order].
 * 2. For each section, filter tasks that match [Selector].
 * 3. If [Section.discard] is true, remove matched tasks from the pool for subsequent sections.
 * 4. Wrap the matched tasks in [AgendaRowItem] with their computed markings.
 * 5. A section with [Section.groupBy] becomes one section per period
 *    ([DateSectionGrouper]); every other section is rendered as declared.
 */
object AgendaEvaluator {
    /**
     * Evaluates [definition] against [tasks] and returns rendered sections.
     *
     * @param tasks The full task list to evaluate. Typically fetched via [aggregateBaseFilter].
     * @param definition The agenda definition to apply.
     * @param today The current date used for relative bucket calculations.
     * @param now The current instant, used for staleness computation in [Selector.Stagnant].
     * @param projectsById The projects resolved once for the whole list, keyed by id —
     *   the name and colour a row carries. Empty when the caller has no projects.
     * @param dateGrouping The grouping the screen chose for date-grouped sections, or
     *   null to keep each section's own default. It is the profile's saved choice
     *   for the Upcoming tab, not a property of the definition.
     */
    fun evaluate(
        tasks: List<Task>,
        definition: AgendaDefinition,
        today: LocalDate,
        now: Instant,
        projectsById: Map<String, Project> = emptyMap(),
        dateGrouping: DateGrouping? = null,
    ): List<RenderedSection> {
        var remaining = tasks.toSet()
        // Resolved once for the whole evaluation. `isBlocked` rebuilds its lookup
        // table per call, so asking per row was quadratic in the task count.
        val blockedIds = Blocking.blockedTaskIds(tasks)
        // How many dates each series has. The row says which work it is one date
        // of, so the count is counted once for the whole list rather than per row.
        val seriesSizes = seriesSizes(tasks)
        // The ordering projection, computed the same once-per-evaluation way as the
        // blocking one: `FollowUp` asks whether the task is in this set, not whether
        // some other task points at it.
        val followUpIds = tasks.filter { it.followsUp.isNotEmpty() }.map { it.id }.toSet()
        val context =
            SelectorContext(
                today = today,
                now = now,
                blockedIds = blockedIds,
                followUpIds = followUpIds,
            )
        return definition.sections
            .sortedBy { it.order }
            .flatMap { section ->
                val matched = remaining.filter { section.selector.matches(it, context) }
                if (matched.isEmpty()) return@flatMap emptyList()

                if (section.discard) {
                    remaining = remaining - matched.toSet()
                }

                val rows =
                    matched.map { task ->
                        val project = task.projectId?.value?.let { projectsById[it] }
                        AgendaRowItem(
                            task = task,
                            markings =
                                computeTaskMarkings(
                                    task = task,
                                    today = today,
                                    isBlocked = task.id in blockedIds,
                                ),
                            seriesDates = task.seriesId?.let { seriesSizes[it.value] },
                            project = project?.name,
                            projectColor = project?.color,
                        )
                    }

                val grouping = section.groupBy
                if (grouping != null) {
                    DateSectionGrouper.group(section, rows, today, dateGrouping ?: grouping)
                } else {
                    listOf(
                        RenderedSection(
                            id = section.effectiveId,
                            name = section.name,
                            tasks = rows.toImmutableList(),
                            badge = rows.size.takeIf { it > 0 },
                            prefill = section.prefill,
                        ),
                    )
                }
            }
    }

    /**
     * Returns true if [task] matches the given [Selector] predicate.
     *
     * @param context Contains [SelectorContext.today] for resolving date buckets,
     *                [SelectorContext.now] for staleness, and [SelectorContext.blockedIds]
     *                for blocked-task matching.
     *
     * @deprecated Use [Selector.matches] directly. This shim exists for one release
     * cycle to allow callers to migrate. It forwards to `selector.matches(task, context)`.
     */
    @Deprecated(
        message = "Use selector.matches(task, context) directly",
        replaceWith = ReplaceWith("selector.matches(task, context)"),
    )
    fun matches(
        task: Task,
        selector: Selector,
        context: SelectorContext,
    ): Boolean = selector.matches(task, context)
}
