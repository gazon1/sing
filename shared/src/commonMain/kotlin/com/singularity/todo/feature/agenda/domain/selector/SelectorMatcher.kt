package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate

/**
 * Returns `true` if [this] selector matches [task] on [today].
 *
 * Each [Selector] variant implements this extension with its own predicate logic.
 * This is the **single source of truth** for selector matching — never duplicate
 * the predicate logic elsewhere.
 *
 * ## Usage
 *
 * ```kotlin
 * if (selector.matches(task, today)) { ... }
 * ```
 *
 * ## Coverage
 *
 * All 13 Selector variants are covered: DateBucket, DateRange, Statuses,
 * Priorities, Tags, Projects, Pinned, Completed, Overdue, Regexp,
 * AllOf, AnyOf, Not, Anything.
 *
 * @see Selector
 */
fun Selector.matches(task: Task, today: LocalDate): Boolean = when (this) {
    is Selector.DateBucket -> matches(task, today)

    is Selector.DateRange ->
        task.dueDate != null &&
        task.dueDate >= from && task.dueDate <= to

    is Selector.Statuses -> when {
        statuses.isEmpty() -> false

        TaskStatus.All in statuses -> true

        else -> {
            val activeMatch = TaskStatus.Active in statuses && !task.isCompleted
            val completedMatch = TaskStatus.Completed in statuses && task.isCompleted
            activeMatch || completedMatch
        }
    }

    is Selector.Priorities -> if (atMost) {
        priorities.contains(task.priority)
    } else {
        !priorities.contains(task.priority)
    }

    is Selector.Tags -> when {
        ids.isEmpty() && matchAll -> true

        // empty+matchAll is trivially true
        ids.isEmpty() -> false

        matchAll -> ids.all { task.tags.contains(it) }

        else -> ids.any { task.tags.contains(it) }
    }

    is Selector.Projects -> ids.contains(task.projectId)

    is Selector.Pinned -> task.isPinned

    is Selector.Completed -> task.isCompleted

    is Selector.Overdue ->
        task.dueDate != null &&
        task.dueDate < today && !task.isCompleted

    is Selector.Regexp -> query.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(task.title)

    is Selector.AllOf -> children.all { it.matches(task, today) }

    is Selector.AnyOf -> children.any { it.matches(task, today) }

    is Selector.Not -> !child.matches(task, today)

    is Selector.Anything -> true
}

/**
 * Matches a [Selector.DateBucket] against [task] using [today].
 *
 * Exposed as a separate named function so `DateBucket` has its own dedicated
 * dispatch point — matching the per-variant convention used throughout this package.
 */
private fun Selector.DateBucket.matches(task: Task, today: LocalDate): Boolean {
    val range = bucket.toDateRange(today)
    return when (bucket) {
        RelativeBucket.Overdue ->
            task.dueDate != null &&
                task.dueDate < today && !task.isCompleted

        RelativeBucket.NoDate -> task.dueDate == null

        else ->
            task.dueDate != null &&
                task.dueDate >= range.from && task.dueDate <= range.to
    }
}
