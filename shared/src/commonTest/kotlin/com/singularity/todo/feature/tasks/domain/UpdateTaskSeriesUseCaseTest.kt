package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskSeriesUseCase
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UpdateTaskSeriesUseCase] — the write that changes a whole series.
 *
 * The use case is what makes "the same for all of them" real: it reads the
 * members that are in the database *now*, changes each of them and writes them
 * back. These tests pin the three things that can go wrong: a member that is
 * missed, a member that belongs to somebody else, and a series that is not
 * there at all.
 */
@Tag("fast")
class UpdateTaskSeriesUseCaseTest {

    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val seriesId = TaskSeriesId("series-1")

    /** A fixed timestamp, so the fixtures do not depend on the wall clock. */
    private val fixedInstant: kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000)

    /** A repository whose current user is [user]. */
    private fun repoFor(user: UserId) = FakeTaskRepository(
        explicitCurrentUser = FakeProfileAwareCurrentUser(initialUserId = user),
    )

    /** One member of the series, on its own date. */
    private fun member(
        id: String,
        title: String = "Pay the rent",
        user: UserId = alice,
        dueDate: LocalDate = LocalDate(2026, 10, 5),
    ) = Task(
        id = TaskId(id),
        title = title,
        dueDate = dueDate,
        userId = user,
        seriesId = seriesId,
        createdAt = fixedInstant,
        updatedAt = fixedInstant,
    )

    // ─── The change reaches every member ──────────────────────────────────

    @Test
    fun `a change reaches every member of the series`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("s1", dueDate = LocalDate(2026, 10, 5)),
            member("s2", dueDate = LocalDate(2026, 10, 12)),
            member("s3", dueDate = LocalDate(2026, 10, 19)),
        )
        val useCase = UpdateTaskSeriesUseCase(repo)

        val result = useCase.updateSeries(seriesId) { it.copy(title = "Pay the rent in full") }
        advanceUntilIdle()

        assertTrue(result.isSuccess, "the write of a live series succeeds")
        assertEquals(3, result.getOrNull()?.size, "every member is written back")

        val stored = repo.observeSeries(seriesId).first()
        assertEquals(3, stored.size)
        assertTrue(
            stored.all { it.title == "Pay the rent in full" },
            "a series whose rows carry three titles says three things about one job",
        )
    }

    @Test
    fun `a change to one member does not touch the others' dates`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("s1", dueDate = LocalDate(2026, 10, 5)),
            member("s2", dueDate = LocalDate(2026, 10, 12)),
        )
        val useCase = UpdateTaskSeriesUseCase(repo)

        useCase.updateSeries(seriesId) { it.copy(title = "Changed") }
        advanceUntilIdle()

        val dates = repo.tasks.value.values.associate { it.id.value to it.dueDate }
        assertEquals(LocalDate(2026, 10, 5), dates["s1"])
        assertEquals(LocalDate(2026, 10, 12), dates["s2"], "the dates of the occurrences are their own")
    }

    // ─── Another user's member is not touched ─────────────────────────────

    @Test
    fun `a member that belongs to another user is left alone`() = runTest {
        val repo = repoFor(alice)
        repo.seed(
            member("s1"),
            member("s2"),
            member("b1", title = "Bob's rent", user = bob),
        )
        val useCase = UpdateTaskSeriesUseCase(repo)

        useCase.updateSeries(seriesId) { it.copy(title = "Changed") }
        advanceUntilIdle()

        assertEquals(
            "Bob's rent",
            repo.tasks.value[TaskId("b1").value]?.title,
            "another profile's row is not this profile's to change",
        )
    }

    // ─── A series that is not there ───────────────────────────────────────

    @Test
    fun `a series with no members is a failure`() = runTest {
        val repo = repoFor(alice)
        val useCase = UpdateTaskSeriesUseCase(repo)

        val result = useCase.updateSeries(TaskSeriesId("missing")) { it }

        assertTrue(result.isFailure, "a series that is gone cannot be changed")
    }

    @Test
    fun `archiving a series archives every member`() = runTest {
        val repo = repoFor(alice)
        repo.seed(member("s1"), member("s2"))
        val useCase = UpdateTaskSeriesUseCase(repo)

        val archived = useCase.archiveSeries(seriesId)
        advanceUntilIdle()

        assertEquals(2, archived.getOrNull()?.size, "both members are archived")
        assertEquals(
            setOf("s1", "s2"),
            archived.getOrNull()?.map { it.value }?.toSet(),
            "the undo has to know exactly which occurrences went away",
        )
        assertTrue(
            repo.tasks.value.values.all { it.archivedAt != null },
            "archiving one member and leaving the other live would split the series",
        )
    }

    @Test
    fun `archiving a series that is not there is a failure`() = runTest {
        val repo = repoFor(alice)
        val useCase = UpdateTaskSeriesUseCase(repo)

        val result = useCase.archiveSeries(TaskSeriesId("missing"))

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull(), "the failure says what happened")
    }
}
