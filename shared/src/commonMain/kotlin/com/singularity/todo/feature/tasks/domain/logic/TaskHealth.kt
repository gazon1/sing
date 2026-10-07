package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * What is wrong with a task, classified — pure, and derived from the task list alone.
 *
 * ## Why health is computed, never stored
 *
 * Every value here is derivable from fields already on [Task]. Persisting it would create
 * a second source of truth that disagrees with the row it was derived from: a task edited
 * in a sync push would keep its old health until something recomputed it. This follows the
 * rule `TaskComputed` already states — derived values are never stored in Room
 * (`Computed.kt:8-15`).
 *
 * ## Why buckets and not a score
 *
 * A single "health score" needs a weighting nobody has agreed on: is an overdue task worse
 * than one that has sat untouched for a month? Any number would be invented, and it would
 * hide the reason a task is unhealthy — which is the part the user can act on. Buckets are
 * computed independently and shown as counts, so the reasoning stays visible.
 *
 * ## Why `updatedAt` is not the answer for staleness
 *
 * [TaskCompletionSlot] copies `completedAt` without bumping `updatedAt`, so a task
 * completed an hour ago may carry an `updatedAt` from weeks back. A "stale since last
 * touch" metric built on `updatedAt` would therefore report a just-finished task as
 * neglected. `TaskHealth` takes [lastActivityAt] from the caller instead of deciding here,
 * so the choice of timestamp is visible at the one place that makes it.
 */
enum class TaskHealthBucket {
    /** Past its due date, still open. */
    OVERDUE,

    /** No due date and no movement for longer than the stagnation window. */
    STAGNANT,

    /** Blocked by an unfinished dependency. */
    BLOCKED,

    /** Healthy: open, on schedule, and either undated or recently touched. */
    FINE,
}

/**
 * The outcome of classifying one task.
 *
 * [bucket] is the single answer used for counting; [stalledFor] is carried so the UI can
 * say *how* long, which is more actionable than the bucket alone.
 */
data class TaskHealth(
    val bucket: TaskHealthBucket,
    /** How long since the timestamp health was measured against. Null for non-STAGNANT. */
    val stalledFor: Duration? = null,
) {
    companion object {
        /**
         * A task with no due date and no movement in this long is stagnant.
         *
         * Three weeks, chosen because a fortnight is a normal gap between two touches of
         * a slow-moving task, and a month is when an untouched item has usually been
         * forgotten rather than deferred. It is a parameter rather than a constant so a
         * caller can narrow it, and so a test can pin the boundary instead of asserting
         * today's default.
         */
        val DEFAULT_STAGNATION_WINDOW: Duration = 21.days
    }
}

/**
 * Classifies one task. Pure — no clock, no repository, no platform.
 *
 * Ordering is deliberate and is the whole of the classification: a task that is both
 * overdue and blocked reports [TaskHealthBucket.OVERDUE], because "past its date" is the
 * fact a user can act on today, and hiding it behind "also blocked" would make the list
 * harder to act on rather than easier. The precedence is stated once here so the counts
 * in the UI sum to the task count rather than to "tasks with at least one problem".
 *
 * @param lastActivityAt the timestamp staleness is measured against. The caller chooses it
 *   because choosing wrongly is a product decision, not a mechanical one — see the KDoc on
 *   the file.
 * @param blockedIds ids of tasks blocked by an unfinished dependency, as computed once for
 *   the whole list by [TaskComputed.blockedIds]. Passed in rather than derived here
 *   because deriving it per task makes a whole-list classification O(n²) — measured at
 *   ~1.1s for 4000 tasks, which is the trap `Computed.kt:99-107` documents and
 *   `blockedIds` exists to avoid.
 */
fun Task.healthBucket(
    today: kotlinx.datetime.LocalDate,
    blockedIds: Set<TaskId>,
    lastActivityAt: kotlin.time.Instant,
    now: kotlin.time.Instant,
    stagnationWindow: Duration = TaskHealth.DEFAULT_STAGNATION_WINDOW,
): TaskHealth = when {
    isCompleted || isTrashed -> TaskHealth(TaskHealthBucket.FINE)

    // Precedence 1: past due. A due date in the past is actionable on its own.
    TaskComputed.isOverdue(this, today) -> TaskHealth(TaskHealthBucket.OVERDUE)

    // Precedence 2: blocked. The set was computed against the FULL list, so a dependency
    // outside any filtered view still blocks.
    id in blockedIds -> TaskHealth(TaskHealthBucket.BLOCKED)

    else -> stalledOrFine(lastActivityAt, now, stagnationWindow)
}

/**
 * The one precedence step that takes work, split out so [healthBucket] reads as the
 * precedence table it is.
 */
private fun Task.stalledOrFine(
    lastActivityAt: kotlin.time.Instant,
    now: kotlin.time.Instant,
    stagnationWindow: Duration,
): TaskHealth {
    // Only for open, unblocked, and *undated* tasks. An overdue task that also sits
    // untouched is already reported as overdue, and a task dated next month is not
    // neglected for having not been touched this week.
    if (dueDate != null) return TaskHealth(TaskHealthBucket.FINE)

    val stalled = now - lastActivityAt
    return if (stalled >= stagnationWindow) {
        TaskHealth(TaskHealthBucket.STAGNANT, stalledFor = stalled)
    } else {
        TaskHealth(TaskHealthBucket.FINE)
    }
}

/**
 * Counts the buckets across a whole task list.
 *
 * ## Why the aggregate takes the blocked set rather than the list
 *
 * [TaskComputed.blockedIds] is O(edges), and calling it once per task inside a loop makes
 * classification quadratic — ~1.1s at 4000 tasks, per `Computed.kt:99-107`. Computing it
 * once here is the difference between a tab that opens instantly and one that does not,
 * and it is why [healthBucket] accepts ids rather than a list.
 *
 * ## Why completion rate is a ratio and not a bucket
 *
 * Completed tasks classify as [TaskHealthBucket.FINE] so that the bucket counts sum to
 * "open tasks", which is the list a user can act on. Completion rate is a property of the
 * whole period, not of a task, so it is reported beside the buckets rather than inside
 * them — where it would inflate FINE with work that is already done.
 */
data class TaskHealthSummary(
    val counts: Map<TaskHealthBucket, Int>,
    val totalOpen: Int,
    /** Completed ÷ created over the window; null when nothing was created. */
    val completionRate: Double?,
    val medianStalledFor: Duration?,
) {
    operator fun get(bucket: TaskHealthBucket): Int = counts[bucket] ?: 0

    /** The buckets a user can act on, in the order they should be shown. */
    val actionable: List<TaskHealthBucket> =
        listOf(TaskHealthBucket.OVERDUE, TaskHealthBucket.BLOCKED, TaskHealthBucket.STAGNANT)
}

/**
 * Aggregate [healthBucket] over [allTasks]. Pure.
 *
 * @param lastActivityOf supplies the timestamp each task's staleness is measured against.
 *   A function rather than a field because `updatedAt` is not bumped on completion
 *   (`TaskCompletionSlot.kt:68`), so a caller that wants "touched or finished" says so
 *   here instead of the classifier guessing.
 */
fun summarizeTaskHealth(
    allTasks: List<Task>,
    today: kotlinx.datetime.LocalDate,
    now: kotlin.time.Instant,
    stagnationWindow: Duration = TaskHealth.DEFAULT_STAGNATION_WINDOW,
    lastActivityOf: (Task) -> kotlin.time.Instant,
): TaskHealthSummary {
    val open = allTasks.filter { !it.isCompleted && !it.isTrashed }
    val blocked = TaskComputed.blockedIds(allTasks)

    val perTask = open.map { task ->
        task.healthBucket(today, blocked, lastActivityOf(task), now, stagnationWindow)
    }

    val counts = TaskHealthBucket.entries.associateWith { bucket ->
        perTask.count { it.bucket == bucket }
    }

    val created = allTasks.count { !it.isTrashed }
    val completed = allTasks.count { it.isCompleted }

    val stalled = perTask.mapNotNull { it.stalledFor }.sorted()

    return TaskHealthSummary(
        counts = counts,
        totalOpen = open.size,
        completionRate = if (created == 0) null else completed.toDouble() / created,
        medianStalledFor = if (stalled.isEmpty()) {
            null
        } else {
            stalled[stalled.size / 2]
        },
    )
}
