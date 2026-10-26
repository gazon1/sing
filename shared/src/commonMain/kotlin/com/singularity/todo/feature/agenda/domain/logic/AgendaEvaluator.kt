package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import kotlinx.collections.immutable.toImmutableList
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

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
 * 4. Wrap remaining tasks in [AgendaRowItem] with computed [AgendaBadge]s.
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
     */
    fun evaluate(
        tasks: List<Task>,
        definition: AgendaDefinition,
        today: LocalDate,
        now: Instant,
        projectsById: Map<String, Project> = emptyMap(),
    ): List<RenderedSection> {
        var remaining = tasks.toSet()
        // Resolved once for the whole evaluation. `isBlocked` rebuilds its lookup
        // table per call, so asking per row was quadratic in the task count.
        val blockedIds = Blocking.blockedTaskIds(tasks)
        // How many dates each series has. The row says which work it is one date
        // of, so the count is counted once for the whole list rather than per row.
        val seriesSizes = seriesSizes(tasks)
        val context = SelectorContext(today = today, now = now, blockedIds = blockedIds)
        return definition.sections
            .sortedBy { it.order }
            .mapNotNull { section ->
                val matched = remaining.filter { section.selector.matches(it, context) }
                if (matched.isEmpty()) return@mapNotNull null

                if (section.discard) {
                    remaining = remaining - matched.toSet()
                }

                RenderedSection(
                    id = section.effectiveId,
                    name = section.name,
                    tasks = matched.map { task ->
                        val project = task.projectId?.value?.let { projectsById[it] }
                        AgendaRowItem(
                            task = task,
                            badge = computeBadge(task, today, blockedIds),
                            isBlocked = task.id in blockedIds,
                            seriesDates = task.seriesId?.let { seriesSizes[it.value] },
                            project = project?.name,
                            projectColor = project?.color,
                        )
                    }.toImmutableList(),
                    badge = matched.size.takeIf { it > 0 },
                    prefill = section.prefill,
                )
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
    fun matches(task: Task, selector: Selector, context: SelectorContext): Boolean =
        selector.matches(task, context)

    private fun computeBadge(task: Task, today: LocalDate, blockedIds: Set<TaskId>): AgendaBadge? =
        computeAgendaBadge(task, today, blockedIds)

    /**
     * How many dates each series has.
     *
     * The count is the number of rows that share the id — the dates the work is
     * scheduled on. A task that stands alone is not in the table: it has no
     * series to say anything about.
     */
    private fun seriesSizes(tasks: List<Task>): Map<String, Int> =
        tasks.mapNotNull { it.seriesId?.value }
            .groupingBy { it }
            .eachCount()
}
