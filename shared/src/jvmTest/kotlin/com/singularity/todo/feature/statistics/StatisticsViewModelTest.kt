@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("FunctionSignature")

package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.logic.AttentionSignal
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthBucket
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class StatisticsViewModelTest {
    private val testUserId = UserId("test-user")

    /**
     * Statistics bucket by "today" and "last 7 days", so a real clock makes this
     * suite depend on the day it runs. A fixed instant makes the window explicit:
     * every task below lands inside the same 7-day range.
     *
     * The instant sits early in the UTC day on purpose: in the zone this test
     * injects it is already the next day, and in every zone a test machine is
     * likely to be in it is still this day. The day the ViewModel reads
     * therefore depends on the injected zone, and a ViewModel that read the
     * machine's zone instead would name a different day — which is exactly what
     * this test is here to catch.
     */
    private val testNow: Instant = Instant.parse("2026-01-15T10:00:00Z")

    /**
     * The zone the ViewModel sees.
     *
     * UTC+14 is the far end of the clock on purpose: at [testNow] the day here
     * is one day ahead of the UTC day and of the day in every other ordinary
     * zone, so a test that let the ViewModel fall back to the machine's zone
     * would answer with the wrong day and fail.
     */
    private val testZone: TimeZone = TimeZone.of("Pacific/Kiritimati")

    /**
     * The day the ViewModel sees.
     *
     * The ViewModel reads the day in the injected zone, and the test injects a
     * fixed one — a test that hard-coded a day would disagree with the screen on
     * every machine that is not in that zone, and a test that read the machine's
     * zone would move with the clock.
     */
    private val today: kotlinx.datetime.LocalDate =
        testNow.toLocalDateTime(testZone).date

    private fun task(
        id: String,
        completed: Boolean = false,
        dueDate: kotlinx.datetime.LocalDate? = null,
    ): Task {
        val now = testNow
        return Task(
            id = TaskId.fromString(id),
            title = "Task $id",
            userId = testUserId,
            createdAt = now,
            updatedAt = now,
            completedAt = if (completed) now else null,
            dueDate = dueDate,
        )
    }

    private fun TestScope.createVm(
        repo: FakeTaskRepository = FakeTaskRepository(),
        reporter: CrashReportingPort = NoOpCrashReportingPort(),
        projects: FakeProjectsRepository? = null,
        clock: FakeClock = FakeClock(testNow),
        timeZone: TimeZoneProvider = FixedTimeZone(testZone),
    ): StatisticsViewModel {
        val currentUser = FakeProfileAwareCurrentUser()
        val timeTracking = FakeTimeTrackingRepository(clock)
        val settings = FakeSettingsRepository(testUserId.value)
        return StatisticsViewModel(
            deps =
                StatisticsDeps(
                    taskRepository = repo,
                    timeTrackingRepo = timeTracking,
                    projectsRepo = projects ?: FakeProjectsRepository(currentUser),
                    currentUser = currentUser,
                    clock = clock,
                    settingsRepository = settings,
                    timeZone = timeZone,
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
            repo.complete(TaskId.fromString("t1"))

            // The collectors live in the background scope, and the scheduler is
            // never run here: without active collection the state stays at the
            // one the ViewModel was born with.
            //
            // `advanceUntilIdle()` cannot be used to drain them: the day the
            // Health tab classifies by waits for the next local midnight in an
            // endless loop, so the virtual clock never runs out of work. The
            // tests that need the collectors to run say so with `runCurrent()`.
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
                "statistics.nudges_failed",
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

            // Four collectors observe the task list: the task statistics, the
            // task health, the attention items, and the time-tracking insights.
            assertEquals(4, repo.observeByFilterCalls, "one observation per collector")

            vm.onIntent(StatisticsIntent.SetRange(30))
            runCurrent()
            runCurrent()

            // The range changed, so the two collectors that draw on it observe
            // the list again. The health collector does not depend on the range.
            assertEquals(
                6,
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
            // A failure the retry papers over leaves no trace otherwise, and the
            // crash that happens exactly once is the one nobody ever sees.
            assertTrue(
                reporter.breadcrumbs.any { it.contains("statistics.tasks") },
                "a retried failure must leave a breadcrumb: ${reporter.breadcrumbs}",
            )
        }

    // ── The attention items ───────────────────────────────────────────────────

    /**
     * The attention items reach the state.
     *
     * The list is derived, so the test drives the derivation the way the screen
     * does: a task due today is named, and the state says so.
     *
     * The reason it gives is the part that pins the day: the task is due on
     * the day the injected zone names, and in the machine's own zone it would
     * be due tomorrow. A ViewModel that read the machine's zone would say
     * "Due tomorrow" here and fail.
     */
    @Test
    fun `the attention items reach the state`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("t1", dueDate = today))

            val vm = createVm(repo)
            runCurrent()
            runCurrent()

            val nudges = vm.state.value.nudges
            assertTrue(nudges is AttentionNudgesState.Ready, "the attention items land in the state, got $nudges")
            assertEquals(listOf("task:t1"), nudges.nudges.map { it.key })
            assertEquals("Due today", nudges.nudges.first().reason)
        }

    /**
     * The list follows the day, even when nothing in the database moves.
     *
     * Both readings on the Health tab classify a task by the day it is due
     * relative to, so a night that goes by while the screen stands open makes
     * yesterday's answer today's question: a task due today was still named
     * "due soon" the next morning, and a task that became late was not named
     * at all.
     *
     * The test moves the clock across a local midnight and changes nothing
     * else — no task is added, edited or completed — so the only thing that
     * can bring the new answer is the day itself moving.
     */
    @Test
    fun `the attention list follows the day without a change to the tasks`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("t1", dueDate = today))
            val clock = FakeClock(testNow)

            val vm = createVm(repo, clock = clock)
            runCurrent()
            runCurrent()

            val before = vm.state.value.nudges
            assertTrue(before is AttentionNudgesState.Ready, "the list lands, got $before")
            assertEquals("Due today", before.nudges.first().reason, "the task is due today")

            // The night goes by. The rows do not move.
            clock.advance(1.days)
            // The day the Health tab reads is the one the screen waits for:
            // the next local midnight, in the zone the screen was opened with.
            advanceTimeBy(25.hours)
            runCurrent()
            runCurrent()

            val after = vm.state.value.nudges
            assertTrue(after is AttentionNudgesState.Ready, "the list is still there, got $after")
            assertEquals(
                AttentionSignal.OVERDUE,
                after.nudges.first().signal,
                "the task is a day late now, and the list says so",
            )
            assertEquals("Overdue since yesterday", after.nudges.first().reason)

            // The counts beside it are made by the same day, so they move with
            // it. A list that moved and counts that did not would be two
            // answers about one task.
            val health = vm.state.value.health
            assertTrue(health is TaskHealthState.Ready, "the counts land, got $health")
            assertEquals(1, health.summary[TaskHealthBucket.OVERDUE], "the counts call it late too")
        }

    /**
     * The day is read in the zone the user is in.
     *
     * A user who travels east across the date line reads a different day, and
     * a task due on the day they left behind is late on the day they arrived
     * on. The zone is read again on every computation, so the answer follows
     * the user — a zone captured when the screen opened would go on calling
     * yesterday's task "due today" for the rest of the session.
     *
     * The day is read where it is used, not where the screen was opened: the
     * provider is asked at the moment of the computation. The computation here
     * is triggered by a second task appearing, because a zone change on its own
     * has no event to hang on — the screen hears about it at the next daily
     * refresh, or when it is opened again.
     */
    @Test
    fun `the day is read in the zone the user is in`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("t1", dueDate = LocalDate(2026, 1, 15)))
            val clock = FakeClock(testNow)
            val zone = MutableTimeZone(TimeZone.of("America/Los_Angeles"))

            val vm = createVm(repo, clock = clock, timeZone = zone)
            runCurrent()
            runCurrent()

            val before = vm.state.value.nudges
            assertTrue(before is AttentionNudgesState.Ready, "the list lands, got $before")
            assertEquals(
                "Due today",
                before.nudges.first { it.key == "task:t1" }.reason,
                "in UTC-08:00 the task is due on the current day",
            )

            // The user travels east. The task does not move.
            zone.set(TimeZone.of("Pacific/Kiritimati"))

            // Something else happens, and the screen recomputes — this time in
            // the zone the user is in now.
            repo.add(task("t2", dueDate = LocalDate(2026, 1, 16)))
            runCurrent()
            runCurrent()

            val after = vm.state.value.nudges
            assertTrue(after is AttentionNudgesState.Ready, "the list is still there, got $after")
            val t1After = after.nudges.first { it.key == "task:t1" }
            assertEquals(
                AttentionSignal.OVERDUE,
                t1After.signal,
                "in UTC+14:00 the task is due on the day before",
            )
            assertEquals("Overdue since yesterday", t1After.reason)
            assertEquals(
                "Due today",
                after.nudges.first { it.key == "task:t2" }.reason,
                "in UTC+14:00 the second task is due on the current day",
            )

            // The counts beside the list are made by the same day, so they
            // follow the user too.
            val health = vm.state.value.health
            assertTrue(health is TaskHealthState.Ready, "the counts land, got $health")
            assertEquals(1, health.summary[TaskHealthBucket.OVERDUE], "the counts call it late too")
        }

    /**
     * A failed attention query must not read as "nothing needs attention".
     *
     * An empty list is exactly what a healthy task list looks like, so a broken
     * query standing in for a working one would tell the user there is nothing
     * to do. The failure is a state of its own.
     */
    @Test
    fun `a failed attention query leaves the list unavailable`() =
        runTest {
            val reporter = RecordingCrashReportingPort()

            val vm = createVm(FailingTaskRepository(IllegalStateException("no attention today")), reporter)
            runCurrent()
            settleRetries()

            assertEquals(
                AttentionNudgesState.Unavailable,
                vm.state.value.nudges,
                "a failed query must not read as an empty list",
            )
            assertNotNull(
                reporter.reports.firstOrNull { it.second == "statistics.nudges_failed" },
                "the failed attention query must be reported",
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
 * A fixed zone, so the day the ViewModel reads is the day the test means.
 *
 * The ViewModel asks the injected [TimeZoneProvider] for the zone; a test that
 * let it fall back to the machine's zone would say "today" differently on
 * every machine it runs on.
 */
private class FixedTimeZone(private val zone: TimeZone) : TimeZoneProvider {
    override fun current(): TimeZone = zone
}

/**
 * A zone the test can move.
 *
 * The user's zone is part of the calendar context: a user who travels reads a
 * different day. A fixed zone cannot show that, so this one can be moved
 * while the ViewModel is running.
 */
private class MutableTimeZone(private var zone: TimeZone) : TimeZoneProvider {
    override fun current(): TimeZone = zone

    fun set(zone: TimeZone) {
        this.zone = zone
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
