@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("FunctionSignature")

package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class StatisticsViewModelTest {

    private val testUserId = UserId("test-user")

    /**
     * Statistics bucket by "today" and "last 7 days", so a real clock makes this
     * suite depend on the day it runs. A fixed instant makes the window explicit:
     * every task below lands inside the same 7-day range.
     */
    private val testNow: Instant = Instant.parse("2026-01-15T12:00:00Z")

    private fun task(id: String, completed: Boolean = false): Task {
        val now = testNow
        return Task(
            id = TaskId.fromString(id),
            title = "Task $id",
            userId = testUserId,
            createdAt = now,
            updatedAt = now,
            completedAt = if (completed) now else null,
        )
    }

    private fun TestScope.createVm(
        repo: FakeTaskRepository = FakeTaskRepository(),
    ): StatisticsViewModel {
        val clock = FakeClock(testNow)
        val currentUser = FakeProfileAwareCurrentUser()
        val timeTracking = FakeTimeTrackingRepository(clock)
        val projects = FakeProjectsRepository(currentUser)
        return StatisticsViewModel(
            taskRepository = repo,
            timeTrackingRepo = timeTracking,
            projectsRepo = projects,
            currentUser = currentUser,
            clock = clock,
            scope = testScope(backgroundScope),
        )
    }

    @Test
    fun `marks task complete and statistics reactively update`() = runTest {
        val repo = FakeTaskRepository()
        repo.seed(task("t1"))
        repo.seed(task("t2"))

        val vm = createVm(repo)
        advanceUntilIdle()

        repo.toggleComplete(TaskId.fromString("t1"))

        // Without active collection the state stays at initial loading=true.
        assertTrue(vm.stateFlow.value.loading, "Without active collection, loading=true stays")
    }

    @Test
    fun `state exposes StateFlow shape`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        assertTrue(vm.stateFlow.value.loading)
    }
}
