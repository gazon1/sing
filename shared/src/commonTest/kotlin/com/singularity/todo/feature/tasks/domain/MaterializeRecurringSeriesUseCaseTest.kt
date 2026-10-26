package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesPlanner
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.StartRecurringSeriesUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Filling the window of a recurring series, and starting one around a task.
 *
 * The window is the user's future: the rule's own dates as real rows, open and
 * dated ahead. These tests pin that the rows exist, that they carry the rule, and
 * that a re-plan removes the future the new rule does not give.
 */
@Tag("fast")
class MaterializeRecurringSeriesUseCaseTest {

    private val monday = LocalDate(2026, 6, 15)
    private val clock = FakeClock(Instant.parse("2026-06-15T10:00:00Z"))
    private val repo = FakeTaskRepository()
    private val seriesId = TaskSeriesId("series-1")

    private val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)

    private fun materialize() = MaterializeRecurringSeriesUseCase(repo, FakeUnitOfWork(), clock)

    private fun currentRow(
        dueDate: LocalDate = monday,
        spec: RecurrenceSpec = weekly,
        series: TaskSeriesId = seriesId,
    ) = Task(
        id = TaskId("t1"),
        title = "Buy bread",
        dueDate = dueDate,
        recurrence = spec,
        seriesId = series,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        userId = TestUsers.DEFAULT,
    )

    private fun openFutureRows() = repo.tasks.value.values
        .filter { it.seriesId == seriesId && it.completedAt == null && it.id != TaskId("t1") }

    // ─── Filling the window ───────────────────────────────────────────────────

    @Test
    fun `a weekly window materialises the future as open rows`() = runTest {
        val current = currentRow()
        repo.seed(current)

        val result = materialize()(template = current, rule = weekly, anchor = monday)

        assertTrue(result.isSuccess, "the window is written: ${result.exceptionOrNull()}")
        val future = openFutureRows()
        assertTrue(future.isNotEmpty(), "the future exists as rows")
        assertTrue(future.all { (it.dueDate ?: monday) > monday }, "dated ahead of the current occurrence")
        assertTrue(future.all { it.seriesId == seriesId }, "every occurrence belongs to the series")
        assertTrue(future.all { it.recurrence == weekly }, "every occurrence carries the rule")
        assertTrue(future.all { it.title == "Buy bread" }, "and the work")
    }

    @Test
    fun `materialising twice does not duplicate rows`() = runTest {
        val current = currentRow()
        repo.seed(current)

        materialize()(template = current, rule = weekly, anchor = monday)
        val firstCount = openFutureRows().size
        materialize()(template = current, rule = weekly, anchor = monday)

        assertEquals(firstCount, openFutureRows().size, "a second materialisation is a no-op — no duplicates")
    }

    // ─── Changing the rule re-derives the future ──────────────────────────────

    @Test
    fun `changing the rule replaces the future it no longer gives`() = runTest {
        val current = currentRow()
        repo.seed(current)
        materialize()(template = current, rule = weekly, anchor = monday)
        val weeklyDates = openFutureRows().map { it.dueDate }.toSet()
        assertTrue(weeklyDates.isNotEmpty())

        val monthly = RecurrenceSpec.Monthly(RecurrenceBase.FROM_DUE, 15)
        val reparented = current.copy(recurrence = monthly)
        repo.update(reparented)
        materialize()(template = reparented, rule = monthly, anchor = monday)

        val monthlyDates = openFutureRows().map { it.dueDate }.toSet()
        assertTrue(monthlyDates.isNotEmpty(), "the monthly rule gives its own dates")
        assertEquals(setOf(LocalDate(2026, 7, 15)), monthlyDates, "the monthly window is what the new rule gives")
        assertTrue(
            openFutureRows().none { it.dueDate in (weeklyDates - monthlyDates) },
            "the weekly future the monthly rule does not give is gone",
        )
    }

    @Test
    fun `closed occurrences are untouched by a re-plan`() = runTest {
        val closed = Task(
            id = TaskId("closed-1"),
            title = "Buy bread",
            dueDate = monday.minus(7, DateTimeUnit.DAY),
            recurrence = weekly,
            seriesId = seriesId,
            completedAt = Instant.parse("2026-06-08T10:00:00Z"),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            userId = TestUsers.DEFAULT,
        )
        val current = currentRow()
        repo.seed(closed, current)

        materialize()(template = current, rule = weekly, anchor = monday)

        val storedClosed = repo.get(closed.id)
        assertEquals(closed.completedAt, storedClosed?.completedAt, "history is not rewritten")
        assertEquals(closed.dueDate, storedClosed?.dueDate)
    }

    // ─── The ceiling rotates ──────────────────────────────────────────────────

    @Test
    fun `at the ceiling the oldest closed row leaves the series and loses the rule`() = runTest {
        // Fill the series to the ceiling with closed history plus the current row.
        val history = (0 until TaskSeriesPlanner.MAX_DATES - 1).map { i ->
            val date = monday.minus((TaskSeriesPlanner.MAX_DATES - 1 - i) * 7, DateTimeUnit.DAY)
            Task(
                id = TaskId("h$i"),
                title = "Buy bread",
                dueDate = date,
                recurrence = weekly,
                seriesId = seriesId,
                completedAt = Instant.parse("2026-01-01T00:00:00Z"),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                userId = TestUsers.DEFAULT,
            )
        }
        val current = currentRow()
        repo.seed(*history.toTypedArray(), current)

        materialize()(template = current, rule = weekly, anchor = monday)

        // The rotated row is the oldest closed one — it left the series and keeps
        // no rule, or the launch repair would adopt it back.
        val rotated = repo.tasks.value.values.filter { it.seriesId == null }
        assertTrue(rotated.isNotEmpty(), "the ceiling rotated a row out")
        assertTrue(rotated.none { it.recurrence != null }, "the rotated row carries no rule")

        val seriesRows = repo.tasks.value.values.filter { it.seriesId == seriesId }
        assertTrue(
            seriesRows.size <= TaskSeriesPlanner.MAX_DATES,
            "the series stays at the ceiling: ${seriesRows.size}",
        )
    }

    // ─── The create door ──────────────────────────────────────────────────────

    @Test
    fun `a task that stands alone becomes a series when a rule is set`() = runTest {
        val standalone = Task(
            id = TaskId("solo"),
            title = "Buy bread",
            dueDate = monday,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            userId = TestUsers.DEFAULT,
        )
        repo.seed(standalone)
        val startSeries = StartRecurringSeriesUseCase(repo, clock, TEST_TZ, materialize())

        val result = startSeries(TaskId("solo"), weekly)

        assertTrue(result.isSuccess, "the rule is set: ${result.exceptionOrNull()}")
        val row = repo.get(TaskId("solo"))
        assertEquals(weekly, row?.recurrence, "the row carries the rule")
        assertTrue(row?.seriesId != null, "and it belongs to a series")
        val future = repo.tasks.value.values.filter {
            it.seriesId == row?.seriesId && it.completedAt == null && it.id != TaskId("solo")
        }
        assertTrue(future.isNotEmpty(), "the series holds the materialised future occurrences")
    }

    @Test
    fun `a task already in a series is not started twice`() = runTest {
        repo.seed(currentRow())
        val startSeries = StartRecurringSeriesUseCase(repo, clock, TEST_TZ, materialize())

        val result = startSeries(TaskId("t1"), weekly)

        assertTrue(result.isFailure, "setting a rule on a series member is the series' business, not this door's")
    }
}
