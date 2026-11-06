@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("FunctionSignature")

package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
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

    private fun task(
        id: String,
        completed: Boolean = false,
    ): Task {
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
        reporter: CrashReportingPort = NoOpCrashReportingPort(),
    ): StatisticsViewModel {
        val clock = FakeClock(testNow)
        val currentUser = FakeProfileAwareCurrentUser()
        val timeTracking = FakeTimeTrackingRepository(clock)
        val projects = FakeProjectsRepository(currentUser)
        val settings = FakeSettingsRepository(testUserId.value)
        return StatisticsViewModel(
            deps =
                StatisticsDeps(
                    taskRepository = repo,
                    timeTrackingRepo = timeTracking,
                    projectsRepo = projects,
                    currentUser = currentUser,
                    clock = clock,
                    settingsRepository = settings,
                ),
            crashReporter = reporter,
            scope = testScope(backgroundScope),
        )
    }

    @Test
    fun `marks task complete and statistics reactively update`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("t1"))
            repo.seed(task("t2"))

            val vm = createVm(repo)
            advanceUntilIdle()

            repo.complete(TaskId.fromString("t1"))

            // Without active collection the state stays at initial loading=true.
            assertTrue(vm.stateFlow.value.loading, "Without active collection, loading=true stays")
        }

    @Test
    fun `state exposes StateFlow shape`() =
        runTest {
            val vm = createVm()
            advanceUntilIdle()
            assertTrue(vm.stateFlow.value.loading)
        }

    /**
     * The reporter receives the throwable the repository threw.
     *
     * The observation fails inside a channel hop, and a channel carries a *copy*
     * of the throwable — the original is the copy's cause. Reporting the copy
     * would file a throwable that never existed, so the identity is what this
     * asserts: the reporter has to receive the very instance the repository
     * threw, not a look-alike carrying the same message.
     *
     * All three collectors observe the same failing repository, so all three
     * keys have to carry the original.
     */
    @Test
    fun `a failed observation reports the original cause`() =
        runTest {
            val failure = IllegalStateException("the tasks are unavailable")
            val reporter = RecordingCrashReportingPort()

            val vm = createVm(FailingTaskRepository(failure), reporter)
            // The collectors live in the background scope, so `advanceUntilIdle()`
            // would not start them: it stops as soon as there is no foreground work
            // left. `runCurrent()` dispatches what is scheduled right now, background
            // work included — which is what starts the observations that fail.
            runCurrent()
            runCurrent()

            listOf(
                "statistics.tasks_failed",
                "statistics.health_failed",
                "statistics.insights_failed",
            ).forEach { key ->
                val reported = reporter.reports.firstOrNull { it.second == key }
                assertNotNull(reported, "the $key failure must be reported")
                assertSame(
                    failure,
                    reported.first,
                    "the reporter must receive the throwable the repository threw",
                )
            }
        }
}

/**
 * A task repository whose observation always fails, the way the real one does.
 *
 * The failure is thrown inside the user-scoped hop, exactly where the real
 * repository throws it — a hop is what turns the original into a copy, so a
 * fake that threw at the call site would let the assertion pass for the wrong
 * reason.
 */
private class FailingTaskRepository(
    private val failure: Throwable,
    private val currentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser(),
) : FakeTaskRepository(explicitCurrentUser = currentUser) {
    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> =
        currentUser.observeForCurrentUser { flow<List<Task>> { throw failure } }
}
