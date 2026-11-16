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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Holds the two surfaces that classify task health to one answer.
 *
 * ## Why this file exists
 *
 * The precedence **overdue → blocked → stagnant → fine** was written twice: once for
 * the statistics screen's summary and once for the AI tools that report a task's
 * health to a reader. Nothing compared the two, so nothing could see them drift —
 * and they already had: the tools passed the stored staleness number straight into
 * the selector, while the statistics screen read a stored `0` as "use the 21-day
 * default". On a user who had never moved the slider the two agreed; on one who set
 * it to `0` the tools called every undated task stagnant and the card called none.
 *
 * Both now classify through [HealthRules], and this test is what holds them there:
 * it drives the statistics entry point ([summarizeTaskHealthFromSelectors]) and the
 * AI entry point ([TaskToolSupport.health]) over the same tasks and asserts the
 * counts are identical. A future edit that re-forks the rule fails here rather than
 * in front of a user who was told two different things about one task.
 *
 * ## Why the boundary tests are here and not in `TaskHealthTest`
 *
 * They are about the *rule*, and [TaskHealthTest] is about the summary arithmetic.
 * The window boundary in particular is where an off-by-one hides: `Selector.Stagnant`
 * matches on `>=`, so a task idle for exactly the window is stagnant and one idle a
 * millisecond less is not.
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
     * The problem buckets the two surfaces agree on, with empty buckets dropped.
     *
     * Only the three *actionable* buckets are compared: the AI DTO carries the three
     * and no "fine", while the summary carries all four. Zero counts are dropped as
     * well, so the comparison is about which buckets hold tasks rather than about
     * which keys each shape happens to declare.
     */
    private fun Map<TaskHealthBucket, Int>.problems(): Map<TaskHealthBucket, Int> =
        filterKeys { it != TaskHealthBucket.FINE }.filterValues { it > 0 }

    // ── The two surfaces agree ─────────────────────────────────────────────────

    /**
     * The exact case the two used to disagree on: a stored `0`.
     *
     * `0` means "the default", so an undated task idle for 40 days is stagnant and one
     * idle for 5 days is not. The AI path used to read `0` as a zero-day window and
     * call both stagnant.
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

    /**
     * The user's own threshold, on a task either side of it.
     *
     * A 10-day task under a 30-day threshold is not stagnant; under a 7-day one it is.
     * Both surfaces have to move together, because both read the same stored number.
     */
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
     * The full precedence, driven through both surfaces at once.
     *
     * A task that is overdue *and* blocked *and* idle is one task in one bucket, and
     * both surfaces have to put it in the same one.
     */
    @Test
    fun `the precedence is one answer on both surfaces`() {
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
        assertEquals(statistics.problems(), ai.problems(), "both surfaces put each task in one bucket")
    }

    /**
     * Completion and trash are out of the open set on both surfaces.
     *
     * A finished task is not a problem and is not open; a trashed one is neither.
     */
    @Test
    fun `finished and trashed tasks are not counted as problems by either surface`() {
        val tasks =
            listOf(
                task("done", updated = now - 90.days, completed = now - 1.days),
                task("trashed", updated = now - 90.days, trashed = now - 1.days),
            )

        val statistics = statisticsCounts(tasks, stalenessDays = 21)

        assertEquals(0, statistics.getValue(TaskHealthBucket.STAGNANT))
        assertEquals(0, statistics.getValue(TaskHealthBucket.OVERDUE))
        assertEquals(emptyMap(), aiCounts(tasks, 21).problems(), "neither surface reports a problem")
    }

    /**
     * A deferred task is still open work, and both surfaces say so the same way.
     *
     * Deferring moves a task out of the "do it now" lists; it does not finish it. The
     * health rules therefore treat it like any other open undated task — and, more to
     * the point of this file, they treat it *identically on both surfaces*, which is
     * what the user sees when they open the card and the list it counts.
     */
    @Test
    fun `a deferred task is classed the same way on both surfaces`() {
        val tasks = listOf(task("someday-idle", updated = now - 40.days, someday = true))

        val statistics = statisticsCounts(tasks, stalenessDays = 21)
        val ai = aiCounts(tasks, stalenessDays = 21)

        assertEquals(1, statistics.getValue(TaskHealthBucket.STAGNANT))
        assertEquals(statistics.problems(), ai.problems())
    }

    /** Nothing to classify is not an error and not a problem. */
    @Test
    fun `an empty list is empty on both surfaces`() {
        assertEquals(emptyMap(), statisticsCounts(emptyList(), 21).problems())
        assertEquals(emptyMap(), aiCounts(emptyList(), 21).problems(), "no tasks, no problems")
    }

    // ── Boundaries ─────────────────────────────────────────────────────────────

    /**
     * The stagnation window is inclusive: exactly the window is stagnant, one
     * millisecond less is not.
     *
     * `Selector.Stagnant` matches on `elapsed >= threshold`, and the boundary is the
     * one place an off-by-one would hide — a task the card counts and the list drops
     * would differ by exactly this case.
     */
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

    /**
     * The overdue boundary is the day, not the instant: due yesterday is overdue, due
     * today is not, and due tomorrow is not.
     */
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
}

/** A `Duration` component helper that reads better at the boundary than `1.milliseconds`. */
private fun Int.toDurationMillis(): Duration = Duration.parse("${this}ms")
