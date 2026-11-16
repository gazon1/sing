package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.ai.tools.TaskToolSupport
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Holds the two surfaces that classify task health to one answer.
 *
 * The statistics screen and the AI read tools each used to own a copy of the
 * overdue → blocked → stagnant precedence, and the copies had drifted. Both now
 * classify through [HealthRules]; this test drives the two production entry points
 * over one fixture and asserts they agree, so a future edit that re-forks the rule
 * fails here.
 *
 * The boundary tests live here rather than in [TaskHealthTest] because they are about
 * the rule, not about the summary arithmetic.
 */
@Tag("fast")
@Epic("Tasks")
@Feature("Domain logic")
class HealthClassificationAgreementTest {
    private val today: LocalDate = LocalDate(2026, 10, 7)
    private val now: Instant = Instant.parse("2026-10-07T12:00:00Z")
    private val clock = FakeClock(now)
    private val zone = TimeZone.UTC

    private fun task(
        id: String,
        due: LocalDate? = null,
        updated: Instant = now,
        blockedBy: Set<TaskId> = emptySet(),
        completed: Instant? = null,
        trashed: Instant? = null,
        someday: Boolean = false,
    ) = Task(
        id = TaskId(id),
        title = "task $id",
        dueDate = due,
        blockedBy = blockedBy,
        completedAt = completed,
        archivedAt = trashed,
        someday = someday,
        createdAt = now - 100.days,
        updatedAt = updated,
        userId = UserId("u1"),
    )

    /** The statistics screen's counts for [tasks] under [stalenessDays]. */
    private fun statisticsCounts(
        tasks: List<Task>,
        stalenessDays: Int,
    ): Map<TaskHealthBucket, Int> {
        val summary =
            summarizeTaskHealthFromSelectors(
                allTasks = tasks,
                today = today,
                now = now,
                stagnationWindow = stagnationWindowOf(stalenessDays),
                lastActivityOf = { task -> maxOf(task.updatedAt, task.completedAt ?: task.updatedAt) },
            )
        return TaskHealthBucket.entries.associateWith { summary[it] }
    }

    /** The AI tools' counts for the same tasks and the same setting. */
    private fun aiCounts(
        tasks: List<Task>,
        stalenessDays: Int,
    ): Map<TaskHealthBucket, Int> {
        val context = TaskToolSupport.context(tasks, clock, zone)
        val health = TaskToolSupport.health(tasks, context, stalenessDays)
        return mapOf(
            TaskHealthBucket.OVERDUE to health.overdue,
            TaskHealthBucket.BLOCKED to health.blocked,
            TaskHealthBucket.STAGNANT to health.stagnant,
        )
    }

    /**
     * The problem buckets, empty ones dropped.
     *
     * Only the three actionable buckets are compared: the AI DTO carries the three and
     * no "fine", while the summary carries all four. That makes the comparison about
     * which buckets hold tasks rather than which keys each shape declares — which is
     * why every agreement assertion is paired with an explicit non-zero expectation,
     * so a fixture that silently resolved to nothing cannot pass as agreement.
     */
    private fun Map<TaskHealthBucket, Int>.problems(): Map<TaskHealthBucket, Int> =
        filterKeys { it != TaskHealthBucket.FINE }.filterValues { it > 0 }

    // ── The two surfaces agree ─────────────────────────────────────────────────

    /**
     * A stored `0` means the default on both surfaces — the case they used to disagree
     * on, because the AI path read `0` as a zero-day window.
     */
    @Test
    fun `a stored zero is the default window on both surfaces, not a zero-day window`() {
        val tasks =
            listOf(
                task("idle-40", updated = now - 40.days),
                task("idle-5", updated = now - 5.days),
            )

        val statistics = statisticsCounts(tasks, stalenessDays = 0)
        val ai = aiCounts(tasks, stalenessDays = 0)

        assertEquals(1, statistics.getValue(TaskHealthBucket.STAGNANT), "only the 40-day task is stagnant")
        assertEquals(statistics.problems(), ai.problems(), "the two surfaces report the same buckets")
    }

    /** A custom threshold moves both surfaces together. */
    @Test
    fun `a custom threshold moves both surfaces together`() {
        val tasks = listOf(task("idle-10", updated = now - 10.days))

        assertEquals(
            0,
            statisticsCounts(tasks, stalenessDays = 30).getValue(TaskHealthBucket.STAGNANT),
            "10 days idle is under a 30-day threshold",
        )
        assertEquals(
            1,
            statisticsCounts(tasks, stalenessDays = 7).getValue(TaskHealthBucket.STAGNANT),
            "10 days idle is over a 7-day threshold",
        )
        assertEquals(
            statisticsCounts(tasks, 30).problems(),
            aiCounts(tasks, 30).problems(),
            "the tools agree under a wide threshold",
        )
        assertEquals(
            statisticsCounts(tasks, 7).problems(),
            aiCounts(tasks, 7).problems(),
            "the tools agree under a narrow threshold",
        )
    }

    /**
     * The full precedence on both surfaces: a task that is overdue, blocked and idle
     * at once is one task in one bucket, and the surfaces agree on which.
     *
     * Every problem bucket is non-empty here, so a fixture that resolved to nothing
     * could not produce these numbers. This is the test that keeps the `.problems()`
     * comparison honest.
     */
    @Test
    fun `the precedence is one answer on both surfaces, with every problem bucket non-empty`() {
        val blocker = task("blocker")
        val tasks =
            listOf(
                // Overdue, blocked and idle at once: overdue wins.
                task(
                    "everything",
                    due = today.minus(3, DateTimeUnit.DAY),
                    updated = now - 90.days,
                    blockedBy = setOf(blocker.id),
                ),
                // Blocked and idle: blocked wins over stagnant.
                task("blocked-idle", updated = now - 90.days, blockedBy = setOf(blocker.id)),
                // Idle only.
                task("idle", updated = now - 90.days),
                // Nothing wrong.
                task("fine"),
                blocker,
            )

        val statistics = statisticsCounts(tasks, stalenessDays = 21)
        val ai = aiCounts(tasks, stalenessDays = 21)

        assertEquals(1, statistics.getValue(TaskHealthBucket.OVERDUE))
        assertEquals(1, statistics.getValue(TaskHealthBucket.BLOCKED), "'blocked-idle' outranks stagnant")
        assertEquals(1, statistics.getValue(TaskHealthBucket.STAGNANT))
        assertEquals(2, statistics.getValue(TaskHealthBucket.FINE))
        assertEquals(3, statistics.problems().values.sum(), "all three problem buckets carry a task")
        assertEquals(statistics.problems(), ai.problems(), "both surfaces put each task in one bucket")
    }

    // ── Completed and discarded work ───────────────────────────────────────────
    //
    // Two levels of responsibility, deliberately: `HealthRules.bucketOf` answers
    // "fine" for a finished task on its own, and both callers also filter the open set
    // before asking. These tests pin that a task which is overdue or blocked but is
    // finished or discarded is not a problem on either surface, and is not open work.

    @Test
    fun `a finished overdue task is not overdue on either surface`() {
        val tasks =
            listOf(
                task(
                    "finished-late",
                    due = today.minus(5, DateTimeUnit.DAY),
                    completed = now - 1.days,
                ),
            )

        val statistics = statisticsCounts(tasks, stalenessDays = 21)

        assertEquals(0, statistics.getValue(TaskHealthBucket.OVERDUE))
        assertEquals(0, statistics.getValue(TaskHealthBucket.FINE), "a finished task is not open work either")
        assertEquals(emptyMap(), aiCounts(tasks, 21).problems(), "the AI reports no problem")
    }

    @Test
    fun `a discarded overdue task is not overdue on either surface`() {
        val tasks =
            listOf(
                task(
                    "trashed-late",
                    due = today.minus(5, DateTimeUnit.DAY),
                    trashed = now - 1.days,
                ),
            )

        val statistics = statisticsCounts(tasks, stalenessDays = 21)

        assertEquals(0, statistics.getValue(TaskHealthBucket.OVERDUE))
        assertEquals(emptyMap(), aiCounts(tasks, 21).problems(), "the AI reports no problem")
    }

    @Test
    fun `a finished blocked task is not blocked on either surface`() {
        val blocker = task("blocker")
        val tasks =
            listOf(
                task("finished-blocked", blockedBy = setOf(blocker.id), completed = now - 1.days),
                blocker,
            )

        val statistics = statisticsCounts(tasks, stalenessDays = 21)

        assertEquals(0, statistics.getValue(TaskHealthBucket.BLOCKED))
        assertEquals(emptyMap(), aiCounts(tasks, 21).problems(), "the AI reports no problem")
    }

    /** A discarded task is not counted as open work at all. */
    @Test
    fun `a discarded task is not counted in the statistics at all`() {
        val tasks =
            listOf(
                task("trashed", updated = now - 90.days, trashed = now - 1.days),
                task("live", updated = now - 90.days),
            )

        val summary =
            summarizeTaskHealthFromSelectors(
                allTasks = tasks,
                today = today,
                now = now,
                stagnationWindow = stagnationWindowOf(21),
                lastActivityOf = { it.updatedAt },
            )

        assertEquals(1, summary.totalOpen, "only the live task is open work")
        assertEquals(1, summary[TaskHealthBucket.STAGNANT], "and it is the one counted as stagnant")
    }

    /** Both surfaces call the same tasks open. */
    @Test
    fun `both surfaces count the same open set`() {
        val tasks =
            listOf(
                task("live"),
                task("finished", completed = now - 1.days),
                task("trashed", trashed = now - 1.days),
            )

        val context = TaskToolSupport.context(tasks, clock, zone)
        val ai = TaskToolSupport.health(tasks, context, stalenessDays = 21)
        val summary =
            summarizeTaskHealthFromSelectors(
                allTasks = tasks,
                today = today,
                now = now,
                stagnationWindow = stagnationWindowOf(21),
                lastActivityOf = { it.updatedAt },
            )

        assertEquals(1, summary.totalOpen)
        assertEquals(summary.totalOpen, ai.totalOpen, "the two surfaces call the same tasks open")
    }

    /** A deferred task is still open work, and both surfaces class it the same way. */
    @Test
    fun `a deferred task is classed the same way on both surfaces`() {
        val tasks = listOf(task("someday-idle", updated = now - 40.days, someday = true))

        val statistics = statisticsCounts(tasks, stalenessDays = 21)

        assertEquals(1, statistics.getValue(TaskHealthBucket.STAGNANT))
        assertEquals(statistics.problems(), aiCounts(tasks, 21).problems())
    }

    /** Nothing to classify is not an error and not a problem. */
    @Test
    fun `an empty list is empty on both surfaces`() {
        assertEquals(emptyMap(), statisticsCounts(emptyList(), 21).problems())
        assertEquals(emptyMap(), aiCounts(emptyList(), 21).problems(), "no tasks, no problems")
    }

    // ── Boundaries ─────────────────────────────────────────────────────────────

    /** The window is inclusive: exactly the threshold is stagnant, a millisecond less is not. */
    @Test
    fun `a task idle for exactly the window is stagnant, and a millisecond less is not`() {
        val window = 21.days
        val rules = HealthRules(window)
        val context =
            SelectorContext(
                today = today,
                now = now,
                blockedIds = emptySet(),
                lastActivityOf = { it.updatedAt },
            )

        val exactly = task("exactly", updated = now - window)
        val justUnder = task("just-under", updated = now - window + 1.milliseconds)

        assertEquals(TaskHealthBucket.STAGNANT, rules.bucketOf(exactly, context))
        assertEquals(TaskHealthBucket.FINE, rules.bucketOf(justUnder, context))
    }

    /** Overdue is judged by the day: yesterday is late, today and tomorrow are not. */
    @Test
    fun `the overdue boundary is yesterday, today, tomorrow`() {
        val rules = HealthRules()
        val context =
            SelectorContext(
                today = today,
                now = now,
                blockedIds = emptySet(),
                lastActivityOf = { it.updatedAt },
            )

        assertEquals(
            TaskHealthBucket.OVERDUE,
            rules.bucketOf(task("yesterday", due = today.minus(1, DateTimeUnit.DAY)), context),
        )
        assertEquals(
            TaskHealthBucket.FINE,
            rules.bucketOf(task("today", due = today), context),
            "a task due today is not late",
        )
        assertEquals(
            TaskHealthBucket.FINE,
            rules.bucketOf(task("tomorrow", due = today.plus(1, DateTimeUnit.DAY)), context),
        )
    }

    /** A stored `0` is the default; a positive number is that many days. */
    @Test
    fun `the stored staleness setting maps zero to the default and positive numbers to days`() {
        assertNull(stagnationWindowOf(0), "zero means 'leave it at the default'")
        assertNull(stagnationWindowOf(-1), "a negative setting is not a window either")
        assertEquals(7.days, stagnationWindowOf(7))
        assertEquals(90.days, stagnationWindowOf(90))
    }

    // ── The reference day and the last-activity timestamp ─────────────────────

    /**
     * Both surfaces resolve `today` from the same clock, and `lastActivityOf` from the
     * same pair of timestamps.
     *
     * The AI path takes its zone as a parameter and the statistics path takes a date,
     * so this asserts the *inputs agree*: a task due today is "today" to both, and a
     * task's age is measured from the same instant by both.
     */
    @Test
    fun `both surfaces resolve the same reference day and last-activity timestamp`() {
        // Due today: late in a zone a day ahead, on time in UTC.
        val tasks = listOf(task("due-today", due = today, updated = now - 30.days))

        val context = TaskToolSupport.context(tasks, clock, zone)
        val summary =
            summarizeTaskHealthFromSelectors(
                allTasks = tasks,
                today = context.today,
                now = context.now,
                stagnationWindow = stagnationWindowOf(21),
                lastActivityOf = context.lastActivityOf,
            )
        val ai = TaskToolSupport.health(tasks, context, stalenessDays = 21)

        assertEquals(today, context.today, "the AI context resolves today from the clock it is given")
        assertEquals(now, context.now, "and the same instant the summary is built from")
        assertEquals(0, summary[TaskHealthBucket.OVERDUE], "due today is not late in UTC")
        assertEquals(ai.overdue, summary[TaskHealthBucket.OVERDUE], "and the AI reads the same day")
    }
}
