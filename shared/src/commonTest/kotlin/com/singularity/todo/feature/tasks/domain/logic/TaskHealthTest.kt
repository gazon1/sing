package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Task health classification.
 *
 * ## What these tests are actually protecting
 *
 * The interesting property is not that a bucket is returned but that **the counts sum to
 * the task count**. Each task reports exactly one bucket, so a summary whose buckets add
 * up to more than its inputs has a precedence bug — and a precedence bug here is invisible
 * in the UI, where it shows as a task listed under two problems and counted once.
 *
 * So every test asserts the single-bucket invariant directly, and the summary tests assert
 * the arithmetic.
 */
@Tag("fast")
class TaskHealthTest {

    private val today: LocalDate = LocalDate(2026, 10, 7)
    private val now: Instant = Instant.parse("2026-10-07T12:00:00Z")

    private fun task(
        id: String,
        due: LocalDate? = null,
        updated: Instant = now,
        dependsOn: Set<TaskId> = emptySet(),
        completed: Instant? = null,
        trashed: Instant? = null,
    ) = Task(
        id = TaskId(id),
        title = "task $id",
        dueDate = due,
        dependsOn = dependsOn,
        completedAt = completed,
        archivedAt = trashed,
        createdAt = now - 100.days,
        updatedAt = updated,
        userId = UserId("u1"),
    )

    private fun bucketOf(task: Task, all: List<Task>, lastActivity: Instant = task.updatedAt) =
        task.healthBucket(today, TaskComputed.blockedIds(all), lastActivity, now)

    @Test
    fun `an overdue task reports overdue whatever else is true of it`() {
        val t = task("t1", due = today.minus(3, DateTimeUnit.DAY))

        assertEquals(TaskHealthBucket.OVERDUE, bucketOf(t, listOf(t)).bucket)
    }

    /**
     * Precedence is the point of this test: an overdue *and* blocked task must report
     * overdue only, or the summary counts it twice and the sum no longer matches.
     */
    @Test
    fun `overdue outranks blocked and stagnant`() {
        val blocker = task("blocker")
        val t = task(
            "t1",
            due = today.minus(1, DateTimeUnit.DAY),
            updated = now - 90.days,
            dependsOn = setOf(blocker.id),
        )
        val all = listOf(blocker, t)

        assertEquals(TaskHealthBucket.OVERDUE, bucketOf(t, all).bucket)
    }

    @Test
    fun `blocked is reported only when the dependency is unfinished`() {
        val blocker = task("blocker")
        val t = task("t1", dependsOn = setOf(blocker.id))

        assertEquals(TaskHealthBucket.BLOCKED, bucketOf(t, listOf(blocker, t)).bucket)
    }

    @Test
    fun `a blocked task whose dependency is done is not blocked`() {
        val blocker = task("blocker", completed = now - 2.days)
        val t = task("t1", dependsOn = setOf(blocker.id))

        assertEquals(TaskHealthBucket.FINE, bucketOf(t, listOf(blocker, t)).bucket)
    }

    @Test
    fun `an undated untouched task is stagnant`() {
        val t = task("t1", updated = now - 40.days)

        val health = bucketOf(t, listOf(t))

        assertEquals(TaskHealthBucket.STAGNANT, health.bucket)
        assertEquals(40.days, health.stalledFor)
    }

    @Test
    fun `a recently touched task is not stagnant`() {
        val t = task("t1", updated = now - 3.days)

        assertEquals(TaskHealthBucket.FINE, bucketOf(t, listOf(t)).bucket)
    }

    /**
     * A dated task in the future is not neglected for not being touched this week.
     *
     * The alternative — stagnation regardless of dates — would flag every task due next
     * month as stagnant the day after it was created, which is not a signal.
     */
    @Test
    fun `a dated task is never stagnant`() {
        val t = task("t1", due = today.plus(30, DateTimeUnit.DAY), updated = now - 200.days)

        assertEquals(TaskHealthBucket.FINE, bucketOf(t, listOf(t)).bucket)
    }

    /**
     * Completed and trashed tasks never appear as a *problem*, and never appear as open.
     *
     * Both halves matter: a completed task is not overdue, and it is not open work
     * either. It is simply done, and the tab is about what is left.
     */
    @Test
    fun `completed and trashed tasks are neither a problem nor open`() {
        val done = task("done", updated = now - 90.days, completed = now - 1.days)
        val trashed = task("trashed", updated = now - 90.days, trashed = now - 1.days)

        assertEquals(TaskHealthBucket.FINE, bucketOf(done, listOf(done)).bucket)
        assertEquals(TaskHealthBucket.FINE, bucketOf(trashed, listOf(trashed)).bucket)

        val summary = summarizeTaskHealth(listOf(done, trashed), today, now) { it.updatedAt }
        assertEquals(0, summary.totalOpen, "neither is work the user can act on")
        assertTrue(summary.actionable.all { summary[it] == 0 })
    }

    // ── Summary ────────────────────────────────────────────────────────────────

    @Test
    fun `every task lands in exactly one bucket`() {
        val blocker = task("blocker")
        val tasks = listOf(
            task("overdue", due = today.minus(1, DateTimeUnit.DAY)),
            task("blocked", dependsOn = setOf(blocker.id)),
            task("stagnant", updated = now - 40.days),
            task("fine"),
            blocker,
        )

        val summary = summarizeTaskHealth(tasks, today, now) { it.updatedAt }

        assertEquals(
            tasks.size,
            TaskHealthBucket.entries.sumOf { summary[it] },
            "buckets must partition the task list — a double count hides in the UI as a " +
                "task shown under two problems and counted once",
        )
        assertEquals(5, summary.totalOpen)
    }

    @Test
    fun `completion rate is completed over created and null with nothing created`() {
        val tasks = listOf(
            task("a", completed = now - 1.days),
            task("b"),
            task("c"),
        )

        val summary = summarizeTaskHealth(tasks, today, now) { it.updatedAt }
        assertEquals(1.0 / 3.0, summary.completionRate)

        val empty = summarizeTaskHealth(emptyList(), today, now, lastActivityOf = { it.updatedAt })
        assertEquals(null, empty.completionRate, "no created tasks means no rate, not 0.0")
    }

    /**
     * FINE means "open and unproblematic", not "completed".
     *
     * The summary counts FINE alongside the problems so the buckets partition the list,
     * and `totalOpen` filters completed rows out — which is why the buckets sum to
     * `totalOpen` rather than to the whole list. Asserting both here pins the
     * relationship a UI header depends on.
     */
    @Test
    fun `the buckets partition the open tasks and totalOpen agrees`() {
        val blocker = task("blocker")
        val open = listOf(
            task("overdue", due = today.minus(1, DateTimeUnit.DAY)),
            task("fine"),
            blocker,
        )
        val all = open + listOf(task("done", completed = now - 1.days))

        val summary = summarizeTaskHealth(all, today, now) { it.updatedAt }

        assertEquals(
            summary.totalOpen,
            TaskHealthBucket.entries.sumOf { summary[it] },
            "bucket counts must sum to totalOpen, or the tab header disagrees with the rows",
        )
        assertEquals(3, summary.totalOpen)
        // Two, not one: `blocker` is itself healthy. A task other tasks wait on is not
        // itself a problem, and counting it as one would report a healthy blocker as
        // needing attention.
        assertEquals(2, summary[TaskHealthBucket.FINE])
        assertEquals(1, summary[TaskHealthBucket.OVERDUE])
        assertEquals(
            listOf(TaskHealthBucket.OVERDUE, TaskHealthBucket.BLOCKED, TaskHealthBucket.STAGNANT),
            summary.actionable,
            "the actionable order is presentation, not preference — overdue first",
        )
    }
}
