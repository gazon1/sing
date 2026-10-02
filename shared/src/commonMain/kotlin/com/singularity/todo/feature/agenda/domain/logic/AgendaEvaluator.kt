package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
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
     */
    fun evaluate(tasks: List<Task>, definition: AgendaDefinition, today: LocalDate): List<RenderedSection> {
        var remaining = tasks.toSet()
        return definition.sections
            .sortedBy { it.order }
            .mapNotNull { section ->
                val matched = remaining.filter { matches(it, section.selector, today) }
                if (matched.isEmpty()) return@mapNotNull null

                if (section.discard) {
                    remaining = remaining - matched.toSet()
                }

                RenderedSection(
                    id = section.effectiveId,
                    name = section.name,
                    tasks = matched.map { task ->
                        AgendaRowItem(
                            task = task,
                            badge = computeBadge(task, section.selector, today, tasks),
                            isBlocked = TaskComputed.isBlocked(task, tasks),
                        )
                    },
                    badge = matched.size.takeIf { it > 0 },
                    prefill = section.prefill,
                )
            }
    }

    /**
     * Returns true if [task] matches the given [Selector] predicate.
     *
     * @param today Used to resolve [RelativeBucket] date ranges.
     *
     * @deprecated Use [Selector.matches] directly. This shim exists for one release
     * cycle to allow callers to migrate. It forwards to `selector.matches(task, today)`.
     */
    @Deprecated(
        message = "Use selector.matches(task, today) directly",
        replaceWith = ReplaceWith("selector.matches(task, today)"),
    )
    fun matches(task: Task, selector: Selector, today: LocalDate): Boolean = selector.matches(task, today)

    private fun computeBadge(task: Task, selector: Selector, today: LocalDate, allTasks: List<Task>): AgendaBadge? =
        computeAgendaBadge(task, today, allTasks)
}
