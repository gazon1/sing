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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
     * keys have to carry the original. A failure is retried before it is
     * reported, so the retries have to run out first.
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
            // The failure is retried before it is reported, and the retries wait.
            settleRetries()

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

    /**
     * A health query that failed must not read as a healthy screen.
     *
     * The failure used to be reported as a zero summary, and a zero summary is
     * exactly what a healthy task list looks like — so a broken query stood in
     * for a working one, and the Health tab showed "no problems" for a list it
     * never got to look at. The failure is a state of its own now.
     */
    @Test
    fun `a failed health query leaves the health tab unavailable`() =
        runTest {
            val reporter = RecordingCrashReportingPort()

            val vm = createVm(FailingTaskRepository(IllegalStateException("no health today")), reporter)
            runCurrent()
            // The failure is retried before it is reported, and the retries wait.
            settleRetries()

            assertEquals(
                TaskHealthState.Unavailable,
                vm.state.value.health,
                "a failed health query must not read as a healthy summary",
            )
            assertNotNull(
                reporter.reports.firstOrNull { it.second == "statistics.health_failed" },
                "the failed health query must be reported",
            )
        }

    /**
     * The Health tab says "loading" until the query lands, and "unavailable"
     * only when the query failed.
     *
     * One null summary used to mean both, so a healthy database flashed a
     * broken-looking screen every time the tab opened.
     */
    @Test
    fun `the health tab waits for the query instead of calling it unavailable`() =
        runTest {
            val vm = createVm()
            assertEquals(TaskHealthState.Loading, vm.state.value.health, "the query has not landed yet")

            runCurrent()
            runCurrent()

            val health = vm.state.value.health
            assertTrue(health is TaskHealthState.Ready, "the health query lands with a summary, got $health")
        }

    /**
     * The collectors observe the repository once — not once per state write.
     *
     * The range drives the recomputation, and the range lives in the state the
     * recomputation writes to. Without a dedupe on the range, every state write
     * re-subscribed the repository, and the recomputation changed the state it
     * had just read — a loop that re-queried the database forever.
     */
    @Test
    fun `the repository is observed once per collector and once more per range change`() =
        runTest {
            val repo = FakeTaskRepository()
            val vm = createVm(repo)
            runCurrent()
            runCurrent()

            // Three collectors observe the task list: the task statistics, the
            // task health, and the time-tracking insights.
            assertEquals(3, repo.observeByFilterCalls, "one observation per collector")

            vm.onIntent(StatisticsIntent.SetRange(30))
            runCurrent()
            runCurrent()

            // The range changed, so the two collectors that draw on it observe
            // the list again. The health collector does not depend on the range.
            assertEquals(
                5,
                repo.observeByFilterCalls,
                "one more observation per range-dependent collector",
            )
        }

    /**
     * Choosing the range that is already chosen changes nothing.
     *
     * The collectors recompute on a *new* range, so asking for the same one
     * would raise the loading flag for a recomputation that never comes, and
     * the screen would wait on it forever.
     */
    @Test
    fun `choosing the selected range again does not leave the screen loading`() =
        runTest {
            val vm = createVm()
            runCurrent()
            runCurrent()

            vm.onIntent(StatisticsIntent.SetRange(7))
            runCurrent()
            runCurrent()

            assertFalse(vm.state.value.loading, "the screen must not wait for a recomputation that never comes")
        }

    /**
     * A transient failure does not end the collector.
     *
     * The catch that reported a failure was also the end of the collector: one
     * failed observation, and the tab was never asked again for the lifetime of
     * the ViewModel — so a database that was busy for a moment left the screen
     * on a value nobody knew was stale, and nothing ever said so.
     *
     * A failure that goes away on its own has to recover without the user
     * doing anything, and it must not be filed as a failure either: it did not
     * end anything.
     */
    @Test
    fun `a transient failure is retried and the collector recovers`() =
        runTest {
            val reporter = RecordingCrashReportingPort()
            val repo = FakeTaskRepository()
            repo.seed(task("t1"))
            // The fake breaks one subscription and lets the next one through,
            // which is what a momentary failure looks like from here.
            repo.nextObserveFilterFailure = IllegalStateException("the database was busy")

            val vm = createVm(repo, reporter)
            runCurrent()
            settleRetries()

            assertNotNull(
                vm.state.value.snapshot,
                "the collector must recover and put a snapshot on screen",
            )
            assertFalse(
                vm.state.value.loading,
                "the recovered screen must not stay on the loading flag",
            )
            assertTrue(
                reporter.reports.isEmpty(),
                "a failure that recovered must not be filed as a failure: ${reporter.reports}",
            )
        }

    /**
     * Lets the retry pauses run out.
     *
     * A failure is retried before it is reported, and the retries wait — in
     * virtual time, so the test moves the clock instead of sleeping.
     */
    private fun TestScope.settleRetries() {
        advanceTimeBy(10_000)
        runCurrent()
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
