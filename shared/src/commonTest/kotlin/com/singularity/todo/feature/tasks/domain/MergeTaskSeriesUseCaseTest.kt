package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.usecase.MergeTaskSeriesUseCase
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [MergeTaskSeriesUseCase] — two series becoming one.
 *
 * The use case answers "these two are the same work". These tests pin what the
 * merged series looks like: one id, one story about the work, and the dates of
 * both.
 */
@Tag("fast")
class MergeTaskSeriesUseCaseTest {

    private val alice = UserId("alice")
    private val first = TaskSeriesId("series-1")
    private val second = TaskSeriesId("series-2")
    private val fixedInstant = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun repoFor(user: UserId) = FakeTaskRepository(
        explicitCurrentUser = FakeProfileAwareCurrentUser(initialUserId = user),
    )

    private fun member(
        id: String,
        series: TaskSeriesId,
        title: String = "Pay the rent",
        dueDate: LocalDate = LocalDate(2026, 10, 5),
    ) = Task(
        id = TaskId(id),
        title = title,
        dueDate = dueDate,
        seriesId = series,
        userId = alice,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
    )

    @Test
    fun `every row ends up in one series`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("a1", first),
            member("b1", second, dueDate = LocalDate(2026, 10, 12)),
        )

        val result = MergeTaskSeriesUseCase(repo)(first, second)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size, "the merged series has the rows of both")
        assertTrue(
            repo.tasks.value.values.all { it.seriesId == first },
            "one work has one id",
        )
    }

    @Test
    fun `the dates of both series are kept`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("a1", first, dueDate = LocalDate(2026, 10, 5)),
            member("b1", second, dueDate = LocalDate(2026, 10, 12)),
        )

        MergeTaskSeriesUseCase(repo)(first, second)
        advanceUntilIdle()

        val dates = repo.tasks.value.values.mapNotNull { it.dueDate }.sorted()
        assertEquals(
            listOf(LocalDate(2026, 10, 5), LocalDate(2026, 10, 12)),
            dates,
            "merging two series gives the work more dates, not fewer",
        )
    }

    @Test
    fun `the rows that join take the shared fields of the series they joined`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("a1", first, title = "Pay the rent"),
            member("b1", second, title = "Something else entirely"),
        )

        MergeTaskSeriesUseCase(repo)(first, second)
        advanceUntilIdle()

        assertTrue(
            repo.tasks.value.values.all { it.title == "Pay the rent" },
            "the merged series says one thing about the work — the one the person was looking at",
        )
    }

    @Test
    fun `a series is not merged with itself`() = runTest {
        val repo = repoFor(alice)
        repo.seed(member("a1", first))

        val result = MergeTaskSeriesUseCase(repo)(first, first)

        assertTrue(result.isFailure, "merging a series with itself is a mistake in the caller")
    }

    @Test
    fun `a series that is not there is a failure`() = runTest {
        val repo = repoFor(alice)
        repo.seed(member("a1", first))

        val result = MergeTaskSeriesUseCase(repo)(first, TaskSeriesId("missing"))

        assertTrue(result.isFailure)
    }

    // ─── The two kinds are not joined ─────────────────────────────────────────

    private val weekly = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval(
        com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase.FROM_DUE,
        1,
        kotlinx.datetime.DateTimeUnit.WEEK,
    )
    private val monthly = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly(
        com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase.FROM_DUE,
        5,
    )

    @Test
    fun `a recurring series and a series of picked dates are not joined`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("a1", first, dueDate = LocalDate(2026, 10, 5)).copy(recurrence = weekly),
            member("b1", second, dueDate = LocalDate(2026, 10, 12)),
        )

        val result = MergeTaskSeriesUseCase(repo)(first, second)
        advanceUntilIdle()

        assertTrue(result.isFailure, "the kinds differ — the join is refused")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("picked dates") == true,
            "the refusal names the reason: ${result.exceptionOrNull()?.message}",
        )
        val stored = repo.tasks.value.values
        assertTrue(
            stored.any { it.seriesId == first } && stored.any { it.seriesId == second },
            "the two series stay two",
        )
    }

    @Test
    fun `two recurring series with different rules are not joined`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("a1", first, dueDate = LocalDate(2026, 10, 5)).copy(recurrence = weekly),
            member("b1", second, dueDate = LocalDate(2026, 10, 12)).copy(recurrence = monthly),
        )

        val result = MergeTaskSeriesUseCase(repo)(first, second)
        advanceUntilIdle()

        assertTrue(result.isFailure, "the rules differ — the join is refused")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("rules") == true,
            "the refusal names the reason: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `two recurring series with the same rule still join`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("a1", first, dueDate = LocalDate(2026, 10, 5)).copy(recurrence = weekly),
            member("b1", second, dueDate = LocalDate(2026, 10, 12)).copy(recurrence = weekly),
        )

        val result = MergeTaskSeriesUseCase(repo)(first, second)
        advanceUntilIdle()

        assertTrue(result.isSuccess, "a join of two series of the same kind is unchanged")
        assertTrue(repo.tasks.value.values.all { it.seriesId == first })
    }
}
