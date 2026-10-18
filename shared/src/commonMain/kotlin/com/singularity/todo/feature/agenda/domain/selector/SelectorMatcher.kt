package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Context passed to [Selector.matches] containing everything the matching logic needs
 * that is not stored on the [Selector] itself.
 *
 * @param today The reference date for resolving [RelativeBucket] date ranges.
 * @param now The current instant, used to compute staleness for [Selector.Stagnant].
 * @param blockedIds The set of task IDs blocked by an unfinished dependency,
 *                   computed once per agenda evaluation via [TaskComputed.blockedIds].
 * @param lastActivityOf Returns the instant of the task's last meaningful activity,
 *                   used for [Selector.Stagnant] staleness. Defaults to [Task.updatedAt]
 *                   to preserve existing Selector semantics; callers that need the
 *                   `max(completedAt, updatedAt)` semantics (e.g. [summarizeTaskHealthFromSelectors])
 *                   pass a custom function.
 */
data class SelectorContext(
    val today: LocalDate,
    val now: Instant,
    val blockedIds: Set<TaskId>,
    val lastActivityOf: (Task) -> Instant = { task -> task.updatedAt },
)

/**
 * Returns `true` if [this] selector matches [task] in the given [context].
 *
 * Each [Selector] variant implements this extension with its own predicate logic.
 * This is the **single source of truth** for selector matching — never duplicate
 * the predicate logic elsewhere.
 *
 * ## Usage
 *
 * ```kotlin
 * if (selector.matches(task, context)) { ... }
 * ```
 *
 * ## Coverage
 *
 * All 16 Selector variants are covered: DateBucket, DateRange, Statuses,
 * Priorities, Tags, Projects, Pinned, Completed, Overdue, Blocked, Stagnant,
 * Regexp, AllOf, AnyOf, Not, Anything.
 *
 * @see Selector
 */
fun Selector.matches(task: Task, context: SelectorContext): Boolean = when (this) {
    is Selector.DateBucket -> matches(task, context.today)

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
            task.dueDate < context.today && !task.isCompleted

    // BLOCKED: task is in the set of tasks blocked by an unfinished dependency.
    // BLOCKED takes precedence over STAGNANT in health classification, so a blocked
    // task that is also stale does NOT match this selector.
    is Selector.Blocked -> task.id in context.blockedIds

    // STAGNANT: undated, incomplete, and not touched for long enough.
    // Excludes blocked tasks (BLOCKED has higher health precedence).
    // Uses lastActivityOf so that a recently-completed task is not flagged as stagnant
    // (completedAt is recent, so max(completedAt, updatedAt) is recent).
    is Selector.Stagnant ->
        task.id !in context.blockedIds &&
            task.dueDate == null &&
            !task.isCompleted &&
            context.now - context.lastActivityOf(task) >= threshold

    // Uses the selector's pre-compiled pattern: see [Selector.Regexp.compiled].
    is Selector.Regexp -> compiled.containsMatchIn(task.title)

    is Selector.AllOf -> children.all { it.matches(task, context) }

    is Selector.AnyOf -> children.any { it.matches(task, context) }

    is Selector.Not -> !child.matches(task, context)

    is Selector.Anything -> true

    is Selector.TimeOfDay -> {
        val taskMinutes = task.dueTime?.let { it.hour * 60 + it.minute } ?: return false
        taskMinutes in minStartMinutes..maxEndMinutes
    }
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
