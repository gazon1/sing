package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.usecase.DetachTaskFromSeriesUseCase
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DetachTaskFromSeriesUseCase] — one occurrence leaving its series.
 *
 * The use case is what says "this date is a different job". These tests pin the
 * two things that can go wrong: the occurrence keeps a claim to a group it has
 * left, and the group it left keeps a member that is not there.
 */
@Tag("fast")
class DetachTaskFromSeriesUseCaseTest {

    private val alice = UserId("alice")
    private val seriesId = TaskSeriesId("series-1")
    private val fixedInstant = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun repoFor(user: UserId) = FakeTaskRepository(
        explicitCurrentUser = FakeProfileAwareCurrentUser(initialUserId = user),
    )

    private fun member(id: String, dueDate: LocalDate = LocalDate(2026, 10, 5)) = Task(
        id = TaskId(id),
        title = "Pay the rent",
        dueDate = dueDate,
        seriesId = seriesId,
        userId = alice,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
    )

    @Test
    fun `the occurrence stands alone afterwards`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("s1"),
            member("s2", dueDate = LocalDate(2026, 10, 12)),
            member("s3", dueDate = LocalDate(2026, 10, 19)),
        )

        val result = DetachTaskFromSeriesUseCase(repo)(TaskId("s2"))
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertNull(
            repo.tasks.value[TaskId("s2").value]?.seriesId,
            "the occurrence that left must not claim to be part of a series",
        )
    }

    @Test
    fun `the occurrence keeps its own date`() = runTest {
        val repo = repoFor(alice)
        repo.seed(member("s1"), member("s2", dueDate = LocalDate(2026, 10, 12)))

        DetachTaskFromSeriesUseCase(repo)(TaskId("s2"))
        advanceUntilIdle()

        assertEquals(
            LocalDate(2026, 10, 12),
            repo.tasks.value[TaskId("s2").value]?.dueDate,
            "the date belongs to the occurrence and stays with it",
        )
    }

    @Test
    fun `the rest of the series keeps its id`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("s1"),
            member("s2", dueDate = LocalDate(2026, 10, 12)),
            member("s3", dueDate = LocalDate(2026, 10, 19)),
        )

        DetachTaskFromSeriesUseCase(repo)(TaskId("s2"))
        advanceUntilIdle()

        assertEquals(
            seriesId,
            repo.tasks.value[TaskId("s1").value]?.seriesId,
            "the series that still has members keeps its id",
        )
    }

    @Test
    fun `the last other member stands alone too`() = runTest {
        val repo = repoFor(alice)
        repo.seed(member("s1"), member("s2", dueDate = LocalDate(2026, 10, 12)))

        DetachTaskFromSeriesUseCase(repo)(TaskId("s2"))
        advanceUntilIdle()

        assertNull(
            repo.tasks.value[TaskId("s1").value]?.seriesId,
            "a series of one member is not a series: the id would promise a group of one",
        )
    }

    @Test
    fun `a task that is not part of a series is left alone`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("s1"),
            Task(
                id = TaskId("lonely"),
                title = "Buy milk",
                userId = alice,
                createdAt = fixedInstant,
                updatedAt = fixedInstant,
            ),
        )

        val result = DetachTaskFromSeriesUseCase(repo)(TaskId("lonely"))
        advanceUntilIdle()

        assertTrue(result.isSuccess, "a task that already stands alone is not an error")
        assertNull(repo.tasks.value[TaskId("lonely").value]?.seriesId)
    }

    @Test
    fun `a task that is not there is a failure`() = runTest {
        val repo = repoFor(alice)

        val result = DetachTaskFromSeriesUseCase(repo)(TaskId("missing"))

        assertTrue(result.isFailure, "there is nothing to detach")
    }
}
