package com.singularity.todo.core.work

import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag

/**
 * The Desktop executor, tested at the only seam that matters: what happens to work over time.
 *
 * ## Why bounded `advanceTimeBy`, never `advanceUntilIdle`
 *
 * Every loop here re-arms itself. `advanceUntilIdle()` on a self-rescheduling loop never
 * reaches idle — that is not a slow test, it is a non-terminating one, and doing it once in
 * this repository produced 69 GB of test output. So time is advanced by a bounded amount
 * and the count is asserted against that bound.
 *
 * ## Why `backgroundScope`, not a scope built in the test body
 *
 * A scope constructed inside the test body is not owned by the test: the loop outlives the
 * assertion and the next test inherits it. `backgroundScope` is cancelled when the test
 * body finishes, which is the lifecycle a long-lived service actually has here.
 */
@Tag("fast")
class JvmBackgroundWorkSchedulerTest {

    private class RecordingJob(override val id: String, private val onRun: suspend (Int) -> JobOutcome) :
        BackgroundJob {
        var runs: Int = 0
            private set

        override suspend fun run(): JobOutcome {
            runs++
            return onRun(runs)
        }
    }

    private fun catalogOf(vararg jobs: BackgroundJob) =
        ListBackgroundJobCatalog(jobs.toList())

    /**
     * A clock reporting `baseEpochMs` plus however much virtual time the test has advanced.
     *
     * A *frozen* clock looks like it would work here and does not: the loop re-derives its
     * next boundary after every run, so a clock that never advances hands back the same
     * delay forever and the job fires every few minutes until the test's budget runs out —
     * 59 runs inside a five-hour window, when this was written as a frozen clock.
     *
     * Real time advances, which is why production is fine. The property worth pinning is
     * exactly that: the cadence follows the clock.
     */
    private class VirtualClock(val currentTimeMs: () -> Long, val baseEpochMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(baseEpochMs + currentTimeMs())
    }

    @Suppress("NoDirectClockSystem") // default parameter for tests that do not need time control
    private fun schedulerFor(
        scope: TestScope,
        vararg jobs: BackgroundJob,
        clock: Clock = Clock.System,
        zone: TimeZone = TimeZone.UTC,
    ) = JvmBackgroundWorkScheduler(
        catalog = catalogOf(*jobs),
        clock = clock,
        scope = scope.backgroundScope,
        crashReporter = NoOpCrashReportingPort(),
        zone = zone,
    )

    @Test
    fun `scheduling the same job twice runs one loop, not two`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Success }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        scheduler.schedule("job", JobSchedule.Periodic(1.hours))

        advanceTimeBy(2.hours + 1.minutes)

        // Two hours at one-per-hour means two runs. Two loops would make it four, which is
        // exactly the doubling that `ExistingWorkPolicy.KEEP` exists to prevent.
        assertEquals(2, job.runs, "a second schedule() started a second loop")
    }

    @Test
    fun `a body that throws does not kill the loop`() = runTest {
        val job = RecordingJob("job") { attempt ->
            if (attempt < 3) throw IllegalStateException("boom $attempt") else JobOutcome.Success
        }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        advanceTimeBy(3.hours + 1.minutes)

        // This is the regression that matters. An unguarded body takes the loop down with
        // it, and every later run is silently lost for the rest of the session — which is
        // how desktop auto-sync stopped working until somebody read a log file.
        assertEquals(3, job.runs, "the loop died with the first throw instead of surviving it")
    }

    @Test
    fun `a body that returns Failed does not kill the loop`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Failed(IllegalStateException("nope")) }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        advanceTimeBy(3.hours + 1.minutes)

        assertEquals(3, job.runs)
    }

    @Test
    fun `a skipped body is still a run`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Skipped("disabled") }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        advanceTimeBy(2.hours + 1.minutes)

        assertEquals(2, job.runs, "Skipped must not be treated as a reason to stop")
    }

    @Test
    fun `cancel stops the loop`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Success }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        advanceTimeBy(90.minutes)
        val beforeCancel = job.runs

        scheduler.cancel("job")
        advanceTimeBy(5.hours)

        assertEquals(beforeCancel, job.runs, "the loop kept running after cancel()")
    }

    @Test
    fun `rescheduling after cancel re-arms`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Success }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        scheduler.cancel("job")
        advanceTimeBy(2.hours)
        val afterCancel = job.runs

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        // Past the boundary, not exactly on it: a tick scheduled for t=2h has not run at
        // t=2h, and asserting on the boundary is asserting on scheduler internals.
        advanceTimeBy(2.hours + 1.minutes)

        assertEquals(afterCancel + 2, job.runs, "schedule() after cancel() did not re-arm")
    }

    @Test
    fun `runNow runs without disturbing the periodic schedule`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Success }
        val scheduler = schedulerFor(this, job)

        scheduler.schedule("job", JobSchedule.Periodic(1.hours))
        advanceTimeBy(30.minutes)
        val beforeNow = job.runs

        scheduler.runNow("job")
        advanceTimeBy(1.minutes)

        assertEquals(beforeNow + 1, job.runs, "runNow did not add exactly one run")
    }

    @Test
    fun `two runNow calls for one job do not double-fire`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Success }
        val scheduler = schedulerFor(this, job)

        scheduler.runNow("job")
        scheduler.runNow("job")
        advanceTimeBy(1.minutes)

        assertEquals(1, job.runs, "runNow is not idempotent while a run is in flight")
    }

    @Test
    fun `an unknown job id throws instead of silently doing nothing`() = runTest {
        val scheduler = schedulerFor(this)

        assertFailsWith<IllegalArgumentException> { scheduler.schedule("nope", JobSchedule.OnDemand) }
        assertFailsWith<IllegalArgumentException> { scheduler.runNow("nope") }
    }

    @Test
    fun `cancelling an unscheduled job is a no-op`() = runTest {
        val scheduler = schedulerFor(this)
        scheduler.cancel("never-scheduled")
    }

    @Test
    fun `a daily schedule fires once per boundary, not in a catch-up burst`() = runTest {
        val job = RecordingJob("job") { JobOutcome.Success }
        // Five minutes before the boundary. Without a fixed clock this test would depend on
        // the time of day it runs, and pass or fail by accident.
        // 2026-10-06T02:55Z, five minutes short of a 03:00 boundary.
        val scheduler = schedulerFor(
            scope = this,
            jobs = arrayOf(job),
            clock = VirtualClock({ testScheduler.currentTime }, 1_791_255_300_000L),
        )

        scheduler.schedule("job", JobSchedule.Daily(atHour = 3, atMinute = 0))
        // Ten minutes: crosses the boundary once, then the clock is past it and the next
        // occurrence is tomorrow. Five hours would still be one run — the loop re-derives its
        // boundary after each run rather than replaying the hours it slept through.
        advanceTimeBy(10.minutes)

        assertEquals(1, job.runs, "the daily loop fired more than once inside one window")
    }

    @Test
    fun `the catalogue resolves a registered job and refuses an unregistered one`() {
        val job = RecordingJob("known") { JobOutcome.Success }
        val catalog = catalogOf(job)

        assertEquals("known", catalog.find("known")?.id)
        assertEquals(null, catalog.find("unknown"))
        assertEquals(listOf(job), catalog.all())
    }
}
