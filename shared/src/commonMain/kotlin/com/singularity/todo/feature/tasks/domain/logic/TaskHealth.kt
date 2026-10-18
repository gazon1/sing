package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import com.singularity.todo.feature.tasks.domain.model.Task
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
 * neglected. `summarizeTaskHealthFromSelectors` takes [lastActivityOf] from the caller
 * instead of deciding here, so the choice of timestamp is visible at the one place
 * that makes it.
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
 * Counts the buckets across a whole task list.
 *
 * ## Why the aggregate takes the blocked set rather than the list
 *
 * [TaskComputed.blockedIds] is O(edges), and calling it once per task inside a loop makes
 * classification quadratic — ~1.1s at 4000 tasks, per `Computed.kt:99-107`. Computing it
 * once here is the difference between a tab that opens instantly and one that does not.
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
 * Computes [TaskHealthSummary] using [Selector.matches] for all classification decisions.
 *
 * This is the single canonical path: health classification for the Statistics screen
 * flows through the same [Selector] DSL that the agenda evaluator uses for section filtering.
 * Any changes to Selector semantics are automatically reflected here without a second
 * place to maintain.
 *
 * Precedence: OVERDUE > BLOCKED > STAGNANT > FINE.
 * Overdue and Stagnant are mutually exclusive (Overdue requires `dueDate != null`,
 * Stagnant requires `dueDate == null`), so there is no ambiguity.
 *
 * @param stagnationWindow The threshold for Stagnant, passed as a Duration to align with
 *   the [Selector.Stagnant] interface. A `null` window uses the Selector's built-in
 *   21-day default.
 */
fun summarizeTaskHealthFromSelectors(
    allTasks: List<Task>,
    today: kotlinx.datetime.LocalDate,
    now: kotlin.time.Instant,
    stagnationWindow: Duration? = null,
    lastActivityOf: (Task) -> kotlin.time.Instant,
): TaskHealthSummary {
    val open = allTasks.filter { !it.isCompleted && !it.isTrashed }
    val blockedIds = TaskComputed.blockedIds(allTasks)
    val context = SelectorContext(today = today, now = now, blockedIds = blockedIds, lastActivityOf = lastActivityOf)

    // Selector mirrors — one per TaskHealthBucket with meaningful precedence order.
    val overdueSelector = Selector.DateBucket(RelativeBucket.Overdue)
    val blockedSelector = Selector.Blocked
    val stagnantSelector = stagnationWindow?.let { Selector.Stagnant(it) }
        ?: Selector.Stagnant() // null window → use Selector's built-in default threshold

    val perTask = open.map { task ->
        when {
            overdueSelector.matches(task, context) -> TaskHealth(TaskHealthBucket.OVERDUE)

            blockedSelector.matches(task, context) -> TaskHealth(TaskHealthBucket.BLOCKED)

            stagnantSelector.matches(task, context) -> {
                val stalled = now - lastActivityOf(task)
                TaskHealth(TaskHealthBucket.STAGNANT, stalledFor = stalled)
            }

            else -> TaskHealth(TaskHealthBucket.FINE)
        }
    }

    val counts = TaskHealthBucket.entries.associateWith { bucket ->
        perTask.count { it.bucket == bucket }
    }

    val created = allTasks.count { !it.isTrashed }
    val completed = allTasks.count { it.isCompleted }

    val stalledDurations = perTask.mapNotNull { it.stalledFor }.sorted()

    val medianStalledFor: Duration? = if (stalledDurations.isEmpty()) {
        null
    } else {
        stalledDurations[stalledDurations.size / 2]
    }

    return TaskHealthSummary(
        counts = counts,
        totalOpen = open.size,
        completionRate = if (created == 0) null else completed.toDouble() / created,
        medianStalledFor = medianStalledFor,
    )
}
