package com.singularity.todo.feature.agenda.domain.logic

import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
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
                    name = section.name,
                    tasks = matched.map { task ->
                        AgendaRowItem(
                            task = task,
                            badge = computeBadge(task, section.selector, today),
                        )
                    },
                    badge = matched.size.takeIf { it > 0 },
                )
            }
    }

    /**
     * Returns true if [task] matches the given [Selector] predicate.
     *
     * @param today Used to resolve [RelativeBucket] date ranges.
     */
    fun matches(task: Task, selector: Selector, today: LocalDate): Boolean {
        return when (selector) {
            is Selector.DateBucket -> {
                val range = selector.bucket.toDateRange(today)
                when (selector.bucket) {
                    RelativeBucket.Overdue ->
                        task.dueDate != null &&
                        task.dueDate < today && !task.isCompleted

                    RelativeBucket.NoDate -> task.dueDate == null

                    else ->
                        task.dueDate != null &&
                        task.dueDate >= range.from && task.dueDate <= range.to
                }
            }

            is Selector.DateRange -> {
                task.dueDate != null &&
                    task.dueDate >= selector.from && task.dueDate <= selector.to
            }

            is Selector.Statuses -> {
                if (selector.statuses.isEmpty()) return false
                if (TaskStatus.All in selector.statuses) return true
                val activeMatch = TaskStatus.Active in selector.statuses && !task.isCompleted
                val completedMatch = TaskStatus.Completed in selector.statuses && task.isCompleted
                activeMatch || completedMatch
            }

            is Selector.Priorities -> {
                if (selector.atMost) {
                    selector.priorities.contains(task.priority)
                } else {
                    !selector.priorities.contains(task.priority)
                }
            }

            is Selector.Tag -> task.tags.contains(selector.id)

            is Selector.Projects -> selector.ids.contains(task.projectId)

            is Selector.Pinned -> task.isPinned

            is Selector.Completed -> task.isCompleted

            is Selector.Overdue ->
                task.dueDate != null &&
                task.dueDate < today && !task.isCompleted

            is Selector.Regexp -> {
                val regex = selector.query.toRegex(RegexOption.IGNORE_CASE)
                regex.containsMatchIn(task.title)
            }

            is Selector.AllOf -> selector.children.all { matches(task, it, today) }

            is Selector.AnyOf -> selector.children.any { matches(task, it, today) }

            is Selector.Not -> !matches(task, selector.child, today)

            is Selector.Anything -> true
        }
    }

    private fun computeBadge(task: Task, selector: Selector, today: LocalDate): AgendaBadge? {
        if (task.isPinned) return AgendaBadge.Pinned
        if (task.isCompleted) return AgendaBadge.Completed
        if (task.dueDate == null) return AgendaBadge.NoDate
        if (task.dueDate < today && !task.isCompleted) return AgendaBadge.Overdue
        return null
    }
}
