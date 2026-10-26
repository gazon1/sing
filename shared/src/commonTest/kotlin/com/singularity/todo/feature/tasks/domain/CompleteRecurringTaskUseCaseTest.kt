package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.model.isExhaustedBy
import com.singularity.todo.feature.tasks.domain.usecase.BlockedTasksException
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Closing an occurrence of a repeating task closes it where it is and
 * materialises the next occurrence as a new row.
 *
 * The old roll-forward closed the row and moved its date — the move never touched
 * `completed_at`, so the row was closed *and* dated in the future, and the next
 * occurrence never existed as an open task. These tests pin the new shape: the
 * closed occurrence stays on its date, and the future is real rows.
 */
@Tag("fast")
class CompleteRecurringTaskUseCaseTest {

    private val today = LocalDate(2026, 6, 15)
    private val clock = FakeClock(Instant.parse("2026-06-15T10:00:00Z"))
    private val repo = FakeTaskRepository()

    private val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)

    private fun materialize() = MaterializeRecurringSeriesUseCase(repo, FakeUnitOfWork(), clock)

    private fun useCase() = CompleteRecurringTaskUseCase(
        repo,
        FakeUnitOfWork(),
        clock,
        TEST_TZ,
        RecurrenceCalculator,
        materialize(),
    )

    private fun recurringTask(
        dueDate: LocalDate = today,
        spec: RecurrenceSpec = weekly,
        seriesId: TaskSeriesId = TaskSeriesId("series-1"),
    ) = Task(
        id = TaskId("t1"),
        title = "Buy bread",
        dueDate = dueDate,
        recurrence = spec,
        seriesId = seriesId,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        userId = TestUsers.DEFAULT,
    )

    private fun boundedUntil(endDate: LocalDate) =
        weekly.copy(termination = RecurrenceTermination(endDate))

    /** The rows of the series [id], regardless of state. */
    private fun seriesRows(id: String = "series-1") =
        repo.tasks.value.values.filter { it.seriesId?.value == id }

    // ─── The close does not move the occurrence ──────────────────────────────────

    @Test
    fun `closing an occurrence leaves it on its own date, closed`() = runTest {
        repo.seed(recurringTask())

        val result = useCase()(TaskId("t1"))

        assertTrue(result.isSuccess, "the unblocked close lands: ${result.exceptionOrNull()}")
        val task = repo.get(TaskId("t1"))
        assertNotNull(task?.completedAt, "the occurrence is closed")
        assertEquals(today, task?.dueDate, "the occurrence stays on its own date — the date never moves")
    }

    @Test
    fun `closing materialises the next occurrences as new rows`() = runTest {
        repo.seed(recurringTask())

        useCase()(TaskId("t1"))

        val openAhead = seriesRows().filter { it.completedAt == null && it.id != TaskId("t1") }
        assertTrue(openAhead.isNotEmpty(), "the future exists as open rows")
        assertTrue(openAhead.all { (it.dueDate ?: today) > today }, "the future rows are dated ahead")
        // The next occurrence is a different row, not the closed one moved.
        assertTrue(openAhead.none { it.id == TaskId("t1") }, "the next occurrence is a new row")
    }

    @Test
    fun `the future rows carry the rule and the series id`() = runTest {
        repo.seed(recurringTask())

        useCase()(TaskId("t1"))

        val futureRows = seriesRows().filter { it.completedAt == null && it.id != TaskId("t1") }
        assertTrue(futureRows.all { it.recurrence == weekly }, "every occurrence carries the rule")
        assertTrue(futureRows.all { it.seriesId?.value == "series-1" }, "every occurrence carries the series id")
        assertTrue(futureRows.all { it.title == "Buy bread" }, "the work is inherited")
    }

    @Test
    fun `closing does not shrink the number of open future occurrences`() = runTest {
        repo.seed(recurringTask())
        val before = seriesRows().count { it.completedAt == null } - 1 // excluding the one about to close

        useCase()(TaskId("t1"))

        val after = seriesRows().count { it.completedAt == null }
        assertTrue(after >= before, "the open future must not shrink: $before -> $after")
    }

    // ─── The refusal ──────────────────────────────────────────────────────────

    @Test
    fun `a recurring close is refused while a prerequisite is open`() = runTest {
        repo.seed(recurringTask())
        repo.seed(prerequisite())
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val result = useCase()(TaskId("t1"))

        assertTrue(result.isFailure, "a blocked recurring close must not go through")
        assertTrue(
            result.exceptionOrNull() is BlockedTasksException,
            "the refusal must name the task in the way: ${result.exceptionOrNull()}",
        )
        val task = repo.get(TaskId("t1"))
        assertNull(task?.completedAt, "the occurrence must not be closed")
        assertEquals(today, task?.dueDate, "the occurrence must not move")
        assertTrue(seriesRows().size == 1, "a refusal materialises nothing — the series is exactly as it was")
    }

    @Test
    fun `a confirmed recurring close goes through and materialises`() = runTest {
        repo.seed(recurringTask())
        repo.seed(prerequisite())
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val result = useCase()(TaskId("t1"), force = true)

        assertTrue(result.isSuccess, "the confirmed path must close: ${result.exceptionOrNull()}")
        assertNotNull(repo.get(TaskId("t1"))?.completedAt, "the occurrence is closed")
        assertEquals(today, repo.get(TaskId("t1"))?.dueDate, "and it stays on its own date")
        assertTrue(seriesRows().size > 1, "the future is materialised")
    }

    @Test
    fun `a refused recurring close writes no copy of a missed occurrence`() = runTest {
        // A catch-up series writes a copy of every missed occurrence. If the guard
        // ran after those writes, a refusal would leave the copies behind.
        val staleDue = today.plus(-14, DateTimeUnit.DAY)
        repo.seed(
            recurringTask(
                dueDate = staleDue,
                spec = RecurrenceSpec.Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK),
            ),
        )
        repo.seed(prerequisite())
        repo.setDependency(TaskId("t1"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val result = useCase()(TaskId("t1"))

        assertTrue(result.isFailure, "the close must be refused")
        val series = repo.tasks.value.values.filter { it.title == "Buy bread" }
        assertEquals(1, series.size, "a refusal leaves the series as it was — no copy of a missed occurrence")
    }

    /** The prerequisite that is still open, and the reason the close is refused. */
    private fun prerequisite() = Task(
        id = TaskId("dep"),
        title = "Prerequisite",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        userId = TestUsers.DEFAULT,
    )

    // ─── A rule that has run out ─────────────────────────────────────────────────

    @Test
    fun `an occurrence on the end date still runs`() = runTest {
        // Due today, next weekly occurrence is today+7. Ending on exactly that
        // day must still recur — the end date is inclusive.
        repo.seed(recurringTask(spec = boundedUntil(today.plus(7, DateTimeUnit.DAY))))

        useCase()(TaskId("t1"))

        val task = repo.get(TaskId("t1"))
        assertNull(task?.archivedAt, "must not stop on the end date itself")
        assertNotNull(task?.completedAt, "the occurrence is closed")
        assertTrue(seriesRows().size > 1, "the next occurrence is materialised")
    }

    @Test
    fun `a rule that has run out closes the occurrence and stops the series`() = runTest {
        repo.seed(recurringTask(spec = boundedUntil(today.plus(6, DateTimeUnit.DAY))))

        useCase()(TaskId("t1"))

        val task = repo.get(TaskId("t1"))
        assertNotNull(task?.completedAt, "the occurrence is closed")
        assertEquals(today, task?.dueDate, "it stays on its own date")
        assertNull(task?.archivedAt, "nothing is archived — a closed occurrence is already out of the active list")
        assertEquals(1, seriesRows().size, "the series holds no further occurrence")
    }

    @Test
    fun `all three bases close the occurrence without moving it`() = runTest {
        val bases = listOf(RecurrenceBase.FROM_DUE, RecurrenceBase.FROM_COMPLETION, RecurrenceBase.CATCH_UP)
        bases.forEachIndexed { index, base ->
            val id = TaskId("base-$index")
            repo.seed(
                recurringTask(spec = RecurrenceSpec.Interval(base, 1, DateTimeUnit.WEEK))
                    .copy(id = id, seriesId = TaskSeriesId("series-$index")),
            )

            useCase()(id)

            val task = repo.get(id)
            assertNotNull(task?.completedAt, "base $base must close the occurrence")
            assertEquals(today, task?.dueDate, "base $base must not move the occurrence")
        }
    }

    // ─── The catch-up copies ─────────────────────────────────────────────────────

    @Test
    fun `catch-up still creates historical copies before the series stops`() = runTest {
        // Due two weeks ago, weekly, catch-up base. The missed occurrences are
        // real work that already happened.
        val staleDue = today.plus(-14, DateTimeUnit.DAY)
        repo.seed(
            recurringTask(
                dueDate = staleDue,
                spec = RecurrenceSpec.Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK)
                    .copy(termination = RecurrenceTermination(staleDue.plus(6, DateTimeUnit.DAY))),
            ),
        )

        useCase()(TaskId("t1"))

        val all = repo.tasks.value.values.toList()
        assertTrue(all.size > 1, "catch-up copies are still created, got ${all.size}")
    }

    @Test
    fun `catch-up copies are closed and dated in the past`() = runTest {
        val staleDue = today.plus(-14, DateTimeUnit.DAY)
        repo.seed(
            recurringTask(
                dueDate = staleDue,
                spec = RecurrenceSpec.Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK),
            ),
        )

        useCase()(TaskId("t1"))

        val historical = seriesRows().filter { it.id != TaskId("t1") && it.completedAt != null }
        assertTrue(historical.isNotEmpty(), "the missed occurrences are materialised as closed rows")
        assertTrue(historical.all { (it.dueDate ?: today) <= today }, "and they are dated in the past")
    }

    // ─── The base decides which date the rule is measured from (D10) ──────────

    @Test
    fun `a late close moves a from-completion window and keeps the rows' own state`() = runTest {
        // The current occurrence is due three days ago; "from completion" measures
        // the next occurrences from when the close happens — today — so the window
        // shifts forward by those three days.
        val due = today.minus(3, DateTimeUnit.DAY)
        val spec = RecurrenceSpec.Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.WEEK)
        val series = TaskSeriesId("series-fc")
        repo.seed(recurringTask(dueDate = due, spec = spec, seriesId = series))
        // Fill the window from the due date, as setting the rule while open would.
        materialize()(template = repo.get(TaskId("t1"))!!, rule = spec, anchor = due).getOrThrow()

        // The first future occurrence carries its own state, which a shift must not
        // discard — the move is a date update, not a rebuild.
        val firstFuture = seriesRows("series-fc")
            .filter { (it.dueDate ?: due) > due }
            .minBy { it.dueDate ?: due }
        repo.update(firstFuture.copy(isPinned = true)).getOrThrow()
        val plannedDate = firstFuture.dueDate

        useCase()(TaskId("t1"))

        val moved = repo.tasks.value.values.firstOrNull { it.isPinned }
        assertNotNull(moved, "the future row survives the shift")
        assertEquals(firstFuture.id, moved.id, "the row keeps its identity — it was moved, not rebuilt")
        assertEquals(
            plannedDate?.plus(3, DateTimeUnit.DAY),
            moved.dueDate,
            "the window shifted by exactly how late the close was",
        )
    }

    @Test
    fun `a late close does not move a from-due window`() = runTest {
        val due = today.minus(3, DateTimeUnit.DAY)
        repo.seed(recurringTask(dueDate = due, spec = weekly))
        materialize()(template = repo.get(TaskId("t1"))!!, rule = weekly, anchor = due).getOrThrow()

        val firstFuture = seriesRows().filter { (it.dueDate ?: due) > due }.minBy { it.dueDate ?: due }
        val planned = firstFuture.dueDate

        useCase()(TaskId("t1"))

        val stillThere = repo.tasks.value.values.firstOrNull { it.id == firstFuture.id }
        assertEquals(
            planned,
            stillThere?.dueDate,
            "under from-due the next occurrence is due on the date the rule gives; the window does not move",
        )
    }

    // ─── Value semantics — the reason termination is a constructor property ───

    @Test
    fun `two specs differing only by end date are not equal`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        assertNotEquals(weekly, bounded)
        assertNotEquals(bounded.hashCode(), weekly.hashCode())
    }

    @Test
    fun `copy preserves the termination`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        val changed = bounded.copy(amount = 2)

        assertEquals(bounded.termination, changed.termination)
        assertEquals(2, changed.amount)
    }

    @Test
    fun `every variant carries a termination`() {
        val term = RecurrenceTermination(today)
        val specs = listOf(
            RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK, term),
            RecurrenceSpec.Weekly(RecurrenceBase.FROM_DUE, setOf(1), term),
            RecurrenceSpec.Monthly(RecurrenceBase.FROM_DUE, 15, term),
            RecurrenceSpec.Yearly(RecurrenceBase.FROM_DUE, 6, 15, term),
        )
        specs.forEach { assertEquals(term, it.termination, "${it::class.simpleName} lost its termination") }
        assertTrue(specs.all { it.isExhaustedBy(today.plus(1, DateTimeUnit.DAY)) })
    }

    // ─── Storage — no migration, old rules keep decoding ──────────────────────

    @Test
    fun `a rule written before this field decodes with no termination`() {
        val legacy = """{"_type":"com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval",""" +
            """"base":"FROM_DUE","amount":1,"unit":{"_type":"kotlinx.datetime.DayBased","days":7}}"""

        val decoded = StableJson.decodeFromString(RecurrenceSpec.serializer(), legacy)

        assertNull(decoded.termination, "a stored rule without the field must decode as unbounded")
        assertEquals(1, (decoded as RecurrenceSpec.Interval).amount)
    }

    @Test
    fun `a bounded rule round-trips through storage`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        val encoded = StableJson.encodeToString(RecurrenceSpec.serializer(), bounded)
        val decoded = StableJson.decodeFromString(RecurrenceSpec.serializer(), encoded)

        assertEquals(bounded, decoded)
        assertEquals(RecurrenceTermination(today), decoded.termination)
    }

    @Test
    fun `an unbounded rule carries no termination wrapper`() {
        val encoded = StableJson.encodeToString(RecurrenceSpec.serializer(), weekly)
        assertFalse(
            encoded.contains("\"termination\":{"),
            "an unbounded series must not carry an empty termination wrapper, got $encoded",
        )
        assertNull(StableJson.decodeFromString(RecurrenceSpec.serializer(), encoded).termination)
    }
}
