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
 * The stagnation window a stored staleness setting means.
 *
 * `0` in the setting is "leave it at the default", not "every task is stagnant the
 * moment it is created": a zero-day window would flag each undated task on the day
 * it was made, which is not a signal. So the setting is translated here, once, and
 * the two surfaces that classify by it — the statistics screen and the AI tools —
 * ask this function rather than each deciding for itself. They did decide for
 * themselves, and they disagreed: the tools passed `0` straight into the selector
 * and reported every undated task as stagnant while the statistics tab reported
 * none.
 *
 * @return the window to use, or `null` to leave [Selector.Stagnant] at its own
 *   [Selector.Stagnant.DEFAULT_STAGNATION_THRESHOLD].
 */
fun stagnationWindowOf(stalenessDays: Int): Duration? = if (stalenessDays > 0) stalenessDays.days else null

/**
 * The bucket rule, resolved once and applied to any number of tasks.
 *
 * ## Why this is the only precedence
 *
 * The precedence is a product decision — overdue first, then blocked, then
 * stagnant — and it was written twice: here for the statistics summary and again
 * in the AI tools, with nothing holding the two together. Each copy was free to
 * drift, and the drift would show as one screen calling a task "blocked" while the
 * other called it "stagnant", with no test able to see it because the two agreed
 * on the day they were written.
 *
 * So the rule lives here. The selectors are built once per instance rather than
 * once per task, because the evaluator asks the same question about every task in
 * the list.
 *
 * ## What it does not decide
 *
 * The reference date, the blocked set and the "last touched" timestamp come from
 * [SelectorContext], which the caller builds. That is deliberate: a surface that
 * counts what is *open* filters the finished tasks out before asking (both current
 * callers do), and a surface that reports on one task wants the finished task to
 * say "fine" rather than to disappear.
 *
 * @param stagnationWindow the window to classify by, or `null` for the selector's
 *   own default — see [stagnationWindowOf].
 */
class HealthRules(stagnationWindow: Duration? = null) {
    private val overdue: Selector = Selector.DateBucket(RelativeBucket.Overdue)
    private val blocked: Selector = Selector.Blocked
    private val stagnant: Selector =
        stagnationWindow?.let { Selector.Stagnant(it) } ?: Selector.Stagnant()

    /**
     * The bucket [task] belongs to: overdue, else blocked, else stagnant, else fine.
     *
     * A finished task is [TaskHealthBucket.FINE]: it is not late, not waiting and
     * not neglected, whatever the edges around it say.
     */
    fun bucketOf(
        task: Task,
        context: SelectorContext,
    ): TaskHealthBucket {
        if (task.isCompleted) return TaskHealthBucket.FINE

        return when {
            overdue.matches(task, context) -> TaskHealthBucket.OVERDUE
            blocked.matches(task, context) -> TaskHealthBucket.BLOCKED
            stagnant.matches(task, context) -> TaskHealthBucket.STAGNANT
            else -> TaskHealthBucket.FINE
        }
    }
}

/**
 * Counts the buckets across a whole task list.
 *
 * ## Why the aggregate takes the blocked set rather than the list
 *
 * [Blocking.blockedTaskIds] is O(edges), and calling it once per task inside a loop makes
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
 * Precedence: OVERDUE > BLOCKED > STAGNANT > FINE. It is owned by [HealthRules],
 * which the AI tools also classify through, so the screen and the tools cannot
 * disagree about the same task.
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
    val blockedIds = Blocking.blockedTaskIds(allTasks)
    val context = SelectorContext(today = today, now = now, blockedIds = blockedIds, lastActivityOf = lastActivityOf)
    val rules = HealthRules(stagnationWindow)

    val perTask =
        open.map { task ->
            val bucket = rules.bucketOf(task, context)
            // Only a stagnant task carries a duration: it is the one bucket whose
            // answer is a "how long", and the median in the summary is built from it.
            TaskHealth(
                bucket = bucket,
                stalledFor = if (bucket == TaskHealthBucket.STAGNANT) now - lastActivityOf(task) else null,
            )
        }

    val counts =
        TaskHealthBucket.entries.associateWith { bucket ->
            perTask.count { it.bucket == bucket }
        }

    val created = allTasks.count { !it.isTrashed }
    val completed = allTasks.count { it.isCompleted }

    val stalledDurations = perTask.mapNotNull { it.stalledFor }.sorted()

    val medianStalledFor: Duration? =
        if (stalledDurations.isEmpty()) {
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
