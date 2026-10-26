package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.RepairRecurringSeriesUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The launch repair: legacy repeating tasks become series.
 *
 * A legacy row is a rule and no series id — the shape the old one-row model left
 * behind. The repair adopts each such row into a series, materialises the window,
 * and reopens a row the roll-forward left closed **only when the series would
 * otherwise have no open occurrence**. It is idempotent.
 */
@Tag("fast")
class RepairRecurringSeriesUseCaseTest {

    private val today = LocalDate(2026, 6, 15)
    private val clock = FakeClock(Instant.parse("2026-06-15T10:00:00Z"))
    private val repo = FakeTaskRepository()

    /** A fixed instant the closed legacy rows carry. */
    private val closedNow = Instant.parse("2026-06-15T10:00:00Z")

    private val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)

    private fun materialize() = MaterializeRecurringSeriesUseCase(repo, FakeUnitOfWork(), clock)

    private val settings = FakeTasksSettingsRepository()
    private val currentUser = FakeProfileAwareCurrentUser(initialUserId = TestUsers.DEFAULT)

    private fun useCase() = RepairRecurringSeriesUseCase(
        repo,
        FakeUnitOfWork(),
        clock,
        materialize(),
        settings,
        currentUser,
    )

    private fun legacy(
        id: String = "legacy",
        dueDate: LocalDate = today,
        completedAt: Instant? = null,
    ) = Task(
        id = TaskId(id),
        title = "Buy bread",
        dueDate = dueDate,
        recurrence = weekly,
        completedAt = completedAt,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        userId = TestUsers.DEFAULT,
    )

    @Test
    fun `a rolled-forward row becomes the current occurrence of a series`() = runTest {
        // Closed with a future date — exactly the row the old roll-forward left.
        repo.seed(legacy(dueDate = today.plus(7, DateTimeUnit.DAY), completedAt = closedNow))

        val result = useCase()()

        assertTrue(result.isSuccess, "the repair runs: ${result.exceptionOrNull()}")
        assertEquals(1, result.getOrNull(), "one row adopted")
        val row = repo.get(TaskId("legacy"))
        assertNotNull(row?.seriesId, "the row now belongs to a series")
        assertNull(row?.completedAt, "and it is reopened — it is the current occurrence")
        assertTrue(
            repo.tasks.value.values.any { it.seriesId == row?.seriesId && it.id != TaskId("legacy") },
            "the series holds the materialised future",
        )
    }

    @Test
    fun `history stays closed`() = runTest {
        // Closed with a past date — legitimate history.
        repo.seed(legacy(dueDate = today.minus(7, DateTimeUnit.DAY), completedAt = closedNow))

        useCase()()

        val row = repo.get(TaskId("legacy"))
        assertNotNull(row?.seriesId, "the row joins a series")
        assertNotNull(row?.completedAt, "but a row closed in the past stays closed — it is finished work")
    }

    @Test
    fun `the repair is idempotent`() = runTest {
        repo.seed(legacy(dueDate = today, completedAt = null))

        useCase()()
        val afterFirst = repo.tasks.value.values.size
        // A fresh marker, so this pass really reads the candidates instead of
        // being skipped: idempotency has to hold for a profile the gate does not
        // remember, too.
        val second = RepairRecurringSeriesUseCase(
            repo,
            FakeUnitOfWork(),
            clock,
            materialize(),
            FakeTasksSettingsRepository(),
            currentUser,
        )
        val result = second()

        assertEquals(0, result.getOrNull(), "a second launch finds nothing to repair")
        assertEquals(afterFirst, repo.tasks.value.values.size, "and no occurrence is duplicated")
    }

    @Test
    fun `a second launch in the same profile performs no repair query`() = runTest {
        repo.seed(legacy(dueDate = today, completedAt = null))

        val first = useCase()()
        assertEquals(1, first.getOrNull(), "the first launch adopts the legacy row")
        assertEquals(1, repo.legacyRecurringQueries, "the first launch asks the candidate query once")

        val second = useCase()()

        assertEquals(0, second.getOrNull(), "a repaired profile has nothing to adopt")
        assertEquals(
            1,
            repo.legacyRecurringQueries,
            "the marker means a second launch runs no repair query at all — " +
                "the count is the only way to tell the gate from a query that found nothing",
        )
    }

    @Test
    fun `a row that already belongs to a series is not touched`() = runTest {
        repo.seed(legacy().copy(seriesId = com.singularity.todo.feature.tasks.domain.model.TaskSeriesId("existing")))

        val result = useCase()()

        assertEquals(0, result.getOrNull(), "a row that already carries a series id is not a candidate")
    }

    @Test
    fun `a repeating task with no rule-carriers at all is a clean pass`() = runTest {
        repo.seed(
            Task(
                id = TaskId("plain"),
                title = "Buy milk",
                dueDate = today,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                userId = TestUsers.DEFAULT,
            ),
        )

        val result = useCase()()

        assertEquals(0, result.getOrNull(), "an ordinary task is not a legacy repeating task")
    }
}
