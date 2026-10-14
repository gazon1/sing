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
 * Task health classification via [summarizeTaskHealthFromSelectors].
 *
 * ## What these tests protect
 *
 * The interesting property is that **the bucket counts sum to the task count**.
 * Each task reports exactly one bucket, so a summary whose buckets add up to more than
 * its inputs has a precedence bug — and a precedence bug here is invisible in the UI,
 * where it shows as a task listed under two problems and counted once.
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

    // ── Precedence ─────────────────────────────────────────────────────────────

    /**
     * Overdue outranks blocked and stagnant — a task past its due date is the most
     * actionable thing, regardless of other problems.
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
        val summary = summarizeTaskHealthFromSelectors(
            listOf(blocker, t), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(1, summary[TaskHealthBucket.OVERDUE])
        assertEquals(0, summary[TaskHealthBucket.BLOCKED])
        assertEquals(0, summary[TaskHealthBucket.STAGNANT])
    }

    @Test
    fun `blocked is reported only when dependency is unfinished`() {
        val blocker = task("blocker")
        val t = task("t1", dependsOn = setOf(blocker.id))

        val summary = summarizeTaskHealthFromSelectors(
            listOf(blocker, t), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(1, summary[TaskHealthBucket.BLOCKED])
    }

    @Test
    fun `a blocked task whose dependency is done is not blocked`() {
        val blocker = task("blocker", completed = now - 2.days)
        val t = task("t1", dependsOn = setOf(blocker.id))

        val summary = summarizeTaskHealthFromSelectors(
            listOf(blocker, t), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(0, summary[TaskHealthBucket.BLOCKED])
        assertEquals(1, summary[TaskHealthBucket.FINE])
    }

    @Test
    fun `an undated untouched task is stagnant`() {
        val t = task("t1", updated = now - 40.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(t), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(1, summary[TaskHealthBucket.STAGNANT])
        assertEquals(40.days, summary.medianStalledFor)
    }

    @Test
    fun `a recently touched task is not stagnant`() {
        val t = task("t1", updated = now - 3.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(t), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(0, summary[TaskHealthBucket.STAGNANT])
        assertEquals(1, summary[TaskHealthBucket.FINE])
    }

    /**
     * A dated task in the future is not neglected for not being touched this week.
     * The alternative — stagnation regardless of dates — would flag every task due next
     * month as stagnant the day after it was created, which is not a signal.
     */
    @Test
    fun `a dated task is never stagnant`() {
        val t = task("t1", due = today.plus(30, DateTimeUnit.DAY), updated = now - 200.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(t), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(0, summary[TaskHealthBucket.STAGNANT])
        assertEquals(1, summary[TaskHealthBucket.FINE])
    }

    /**
     * Completed and trashed tasks never appear as a *problem*, and never appear as open.
     * Both halves matter: a completed task is not overdue, and it is not open work
     * either.
     */
    @Test
    fun `completed and trashed tasks are neither a problem nor open`() {
        val done = task("done", updated = now - 90.days, completed = now - 1.days)
        val trashed = task("trashed", updated = now - 90.days, trashed = now - 1.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(done, trashed), today, now,
            stagnationWindow = 21.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(0, summary.totalOpen, "neither is work the user can act on")
        assertTrue(summary.actionable.all { summary[it] == 0 })
    }

    // ── Summary ───────────────────────────────────────────────────────────────

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

        val summary = summarizeTaskHealthFromSelectors(tasks, today, now) { it.updatedAt }

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

        val summary = summarizeTaskHealthFromSelectors(tasks, today, now) { it.updatedAt }
        assertEquals(1.0 / 3.0, summary.completionRate)

        val empty = summarizeTaskHealthFromSelectors(emptyList(), today, now) { it.updatedAt }
        assertEquals(null, empty.completionRate, "no created tasks means no rate, not 0.0")
    }

    /**
     * FINE means "open and unproblematic", not "completed".
     *
     * The summary counts FINE alongside the problems so the buckets partition the list,
     * and `totalOpen` filters completed rows out — which is why the buckets sum to
     * `totalOpen` rather than to the whole list.
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

        val summary = summarizeTaskHealthFromSelectors(all, today, now) { it.updatedAt }

        assertEquals(
            summary.totalOpen,
            TaskHealthBucket.entries.sumOf { summary[it] },
            "bucket counts must sum to totalOpen, or the tab header disagrees with the rows",
        )
        assertEquals(3, summary.totalOpen)
        // Two, not one: `blocker` is itself healthy. A task other tasks wait on is not
        // itself a problem.
        assertEquals(2, summary[TaskHealthBucket.FINE])
        assertEquals(1, summary[TaskHealthBucket.OVERDUE])
        assertEquals(
            listOf(TaskHealthBucket.OVERDUE, TaskHealthBucket.BLOCKED, TaskHealthBucket.STAGNANT),
            summary.actionable,
        )
    }

    // ── Selector path specifics ───────────────────────────────────────────────

    /**
     * Null stagnationWindow must use Selector.Stagnant's built-in 21-day default.
     * An undated task untouched for 40 days is therefore stagnant under the default.
     */
    @Test
    fun `fromSelectors uses 21-day default when stagnationWindow is null`() {
        val stagnantTask = task("s", updated = now - 40.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(stagnantTask),
            today,
            now,
            stagnationWindow = null,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(1, summary[TaskHealthBucket.STAGNANT])
        assertEquals(1, summary.totalOpen)
    }

    /**
     * When stagnationWindow is explicitly set (e.g. 30 days), a task untouched for
     * 20 days must NOT be stagnant — it is under the custom threshold.
     */
    @Test
    fun `fromSelectors respects custom stagnationWindow override`() {
        val recentTask = task("s", updated = now - 20.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(recentTask),
            today,
            now,
            stagnationWindow = 30.days,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(0, summary[TaskHealthBucket.STAGNANT], "task is under 30-day threshold")
        assertEquals(1, summary[TaskHealthBucket.FINE])
    }

    /**
     * Disabled stagnation (0 days in settings → null window here) must use Selector's
     * default, not 0 days, because a 0-day threshold would flag every undated task
     * as stagnant on the first day.
     */
    @Test
    fun `fromSelectors with null stagnationWindow never flags recent undated tasks`() {
        val recentTask = task("s", updated = now - 5.days)
        val summary = summarizeTaskHealthFromSelectors(
            listOf(recentTask),
            today,
            now,
            stagnationWindow = null,
            lastActivityOf = { it.updatedAt },
        )

        assertEquals(0, summary[TaskHealthBucket.STAGNANT], "recent task must not be stagnant")
        assertEquals(1, summary[TaskHealthBucket.FINE])
    }

    /**
     * Buckets must sum to totalOpen — the partition invariant.
     */
    @Test
    fun `fromSelectors buckets partition open tasks`() {
        val blocker = task("blocker")
        val tasks = listOf(
            task("overdue", due = today.minus(3, DateTimeUnit.DAY)),
            task("blocked", dependsOn = setOf(blocker.id)),
            task("stagnant", updated = now - 50.days),
            task("fine"),
            blocker,
        )

        val summary = summarizeTaskHealthFromSelectors(tasks, today, now) { it.updatedAt }

        assertEquals(summary.totalOpen, TaskHealthBucket.entries.sumOf { summary[it] })
        assertEquals(5, summary.totalOpen)
    }
}
