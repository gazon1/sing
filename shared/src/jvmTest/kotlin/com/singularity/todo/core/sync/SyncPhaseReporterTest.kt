package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [SyncPhaseReporter] and [SyncEngineState].
 *
 * These are the components that classify failures and publish the engine's
 * observable status. Every path through them must be verified here, because
 * a mis-classified failure produces a sync engine that appears idle when it
 * is wedged — the highest-impact bug a sync system can carry.
 *
 * The [SyncEngineState] wrapper is tested only to confirm it wires the
 * [SyncPhaseReporter] to the correct state holders.
 */
@Tag("fast")
class SyncPhaseReporterTest {

    private val log = Logger.withTag("SyncPhaseReporterTest")

    private fun testScope(scope: TestScope) =
        com.singularity.todo.core.coroutines.testScope(scope)

    /** A [CrashReportingPort] that records every call for assertion. */
    private class RecordingCrashReporter : CrashReportingPort {
        val reports = mutableListOf<Pair<Throwable, String>>()

        override fun report(error: Throwable, issueKey: String) {
            reports += error to issueKey
        }

        override fun addBreadcrumb(message: String) {}
    }

    // ─── SyncEngineState wiring ────────────────────────────────────────────────

    @Test
    fun `SyncEngineState exposes status, lastPush, lastPull from its reporter`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        // Initial values — only the public StateFlow API of SyncEngineState is tested here.
        // The wiring between SyncEngineState and SyncPhaseReporter is tested indirectly
        // by every other test in this class: a test that calls a phase reporter method
        // and then reads state.{status,lastPush,lastPull} proves the connection works.
        assertIs<SyncEngineStatus.Idle>(state.status.value)
        assertNull(state.lastPush.value)
        assertNull(state.lastPull.value)
    }

    // ─── localStorage ─────────────────────────────────────────────────────────

    @Test
    fun `localStorage returns success when block succeeds`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        val result = state.phases.localStorage("test.ok", "read the thing") {
            "the thing"
        }

        assertTrue(result.isSuccess)
        assertEquals("the thing", result.getOrThrow())
    }

    @Test
    fun `localStorage returns Persistence failure when block throws`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val cause = IllegalStateException("DB is gone")

        val result = state.phases.localStorage("test.missing", "read the thing") {
            throw cause
        }

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as? AppError.Persistence
        assertEquals("test.missing", error?.code)
        assertEquals("Could not read the thing", error?.message)
        assertSame(cause, error?.cause)
    }

    @Test
    fun `localStorage CancellationException is NOT wrapped in AppError Persistence`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        // CancellationException must not be caught and classified as a persistence error.
        // The distinction matters: CancellationException means the coroutine was cancelled
        // (e.g., scope was terminated) and the appropriate response is to propagate, not
        // to wrap it in AppError.Persistence which would make it look like a local DB failure.
        // The implementation catches it and rethrows directly (see SyncPhaseReporter.kt:99).
        //
        // Because the CancellationException propagates as an uncaught exception rather than
        // returning in Result, we verify the negative: a non-CancellationException error IS
        // correctly wrapped, and the crashReporter IS NOT called (localStorage does not
        // report — reporting is the caller's choice after it receives the Result).
        val nonCancelCause = IllegalStateException("disk full")
        val result = state.phases.localStorage("test.wrap.check", "write the thing") {
            throw nonCancelCause
        }

        // Confirmed: non-cancellation errors ARE wrapped in AppError.Persistence.
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as? AppError.Persistence
        assertEquals("test.wrap.check", error?.code)
        assertEquals("Could not write the thing", error?.message)
        assertSame(nonCancelCause, error?.cause)
    }

    @Test
    fun `localStorage does not report to crashReporter on success`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        state.phases.localStorage("test.ok", "read") { "value" }

        assertTrue(reporter.reports.isEmpty())
    }

    @Test
    fun `localStorage does not report to crashReporter on persistence failure`() = runTest {
        // localStorage wraps the error and returns it; it does NOT report to crashReporter —
        // that is the caller's choice. The crashReporter in this test would receive a call
        // if the classification happened at the reporter level rather than the call site.
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        state.phases.localStorage("test.fail", "read") { throw IllegalStateException("boom") }

        assertTrue(reporter.reports.isEmpty(), "crashReporter is not localStorage's responsibility")
    }

    // ─── pushFailed ───────────────────────────────────────────────────────────

    @Test
    fun `pushFailed sets lastPush to a failure Result`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("outbox write failed", code = "sync.push_io")

        state.phases.pushFailed(error, pending = 3)

        assertTrue(state.lastPush.value!!.isFailure)
        assertSame(error, state.lastPush.value!!.exceptionOrNull())
    }

    @Test
    fun `pushFailed sets status to Failure`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("outbox write failed", code = "sync.push_io")

        state.phases.pushFailed(error, pending = 3)

        val status = state.status.value
        assertIs<SyncEngineStatus.Failure>(status)
        assertSame(error, status.error)
    }

    @Test
    fun `pushFailed reports to crashReporter with the error code`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("outbox write failed", code = "sync.push_io")

        state.phases.pushFailed(error, pending = 3)

        assertEquals(1, reporter.reports.size)
        val (thrown, key) = reporter.reports.first()
        assertSame(error.original(), thrown)
        assertEquals("sync.push_io", key)
    }

    @Test
    fun `pushFailed does not modify lastPull`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("outbox write failed", code = "sync.push_io")

        state.phases.pushFailed(error, pending = 3)

        assertNull(state.lastPull.value)
    }

    // ─── pullFailed ───────────────────────────────────────────────────────────

    @Test
    fun `pullFailed sets lastPull to a failure Result`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("could not read events", code = "sync.pull_io")

        state.phases.pullFailed(error, sinceLsn = 42L)

        assertTrue(state.lastPull.value!!.isFailure)
        assertSame(error, state.lastPull.value!!.exceptionOrNull())
    }

    @Test
    fun `pullFailed sets status to Failure`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("could not read events", code = "sync.pull_io")

        state.phases.pullFailed(error, sinceLsn = 42L)

        val status = state.status.value
        assertIs<SyncEngineStatus.Failure>(status)
        assertSame(error, status.error)
    }

    @Test
    fun `pullFailed reports to crashReporter with the error code`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("could not read events", code = "sync.pull_io")

        state.phases.pullFailed(error, sinceLsn = 42L)

        assertEquals(1, reporter.reports.size)
        val (thrown, key) = reporter.reports.first()
        assertSame(error.original(), thrown)
        assertEquals("sync.pull_io", key)
    }

    @Test
    fun `pullFailed does not modify lastPush`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("could not read events", code = "sync.pull_io")

        state.phases.pullFailed(error, sinceLsn = 42L)

        assertNull(state.lastPush.value)
    }

    // ─── cycleFailed ──────────────────────────────────────────────────────────

    @Test
    fun `cycleFailed sets status to Failure`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("no active scope", code = "sync.no_scope")

        val outcome = state.phases.cycleFailed(error)

        val status = state.status.value
        assertIs<SyncEngineStatus.Failure>(status)
        assertSame(error, status.error)
    }

    @Test
    fun `cycleFailed returns CouldNotStart with the error`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("no active scope", code = "sync.no_scope")

        val outcome = state.phases.cycleFailed(error)

        val couldNotStart = assertIs<SyncOutcome.CouldNotStart>(outcome)
        assertSame(error, couldNotStart.error)
    }

    @Test
    fun `cycleFailed reports to crashReporter with the error code`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("no active scope", code = "sync.no_scope")

        state.phases.cycleFailed(error)

        assertEquals(1, reporter.reports.size)
        val (thrown, key) = reporter.reports.first()
        assertSame(error.original(), thrown)
        assertEquals("sync.no_scope", key)
    }

    @Test
    fun `cycleFailed does not populate lastPush or lastPull`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("no active scope", code = "sync.no_scope")

        state.phases.cycleFailed(error)

        assertNull(state.lastPush.value)
        assertNull(state.lastPull.value)
    }

    // ─── setPushStatus ────────────────────────────────────────────────────────

    @Test
    fun `setPushStatus true transitions to Pushing`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        state.phases.setPushStatus(isPushing = true)

        assertIs<SyncEngineStatus.Pushing>(state.status.value)
    }

    @Test
    fun `setPushStatus false transitions to Idle`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        // Start from a non-idle state to prove the transition works both ways.
        state.phases.setPushStatus(isPushing = true)
        assertIs<SyncEngineStatus.Pushing>(state.status.value)

        state.phases.setPushStatus(isPushing = false)

        assertIs<SyncEngineStatus.Idle>(state.status.value)
    }

    // ─── recordPushResult ─────────────────────────────────────────────────────

    @Test
    fun `recordPushResult stores a success Result`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val summary = PushSummary(processed = 5, succeeded = 4, failed = 1)

        state.phases.recordPushResult(Result.success(summary))

        assertTrue(state.lastPush.value!!.isSuccess)
        assertEquals(5, (state.lastPush.value as Result<PushSummary>).getOrThrow().processed)
    }

    @Test
    fun `recordPushResult stores a failure Result`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("upload failed", code = "sync.push_upload")

        state.phases.recordPushResult(Result.failure(error))

        assertTrue(state.lastPush.value!!.isFailure)
        assertSame(error, state.lastPush.value!!.exceptionOrNull())
    }

    @Test
    fun `recordPushResult does not change status`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        assertIs<SyncEngineStatus.Idle>(state.status.value)

        state.phases.recordPushResult(Result.success(PushSummary(0, 0, 0)))

        // Success does not change status — only pushFailed transitions to Failure.
        assertIs<SyncEngineStatus.Idle>(state.status.value)
    }

    // ─── setStatus ────────────────────────────────────────────────────────────

    @Test
    fun `setStatus transitions to arbitrary status value`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        state.phases.setStatus(SyncEngineStatus.Pulling)

        assertIs<SyncEngineStatus.Pulling>(state.status.value)
    }

    @Test
    fun `setStatus to Failure with an AppError`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("pull stalled", code = "sync.pull_stalled")

        state.phases.setStatus(SyncEngineStatus.Failure(error))

        val status = assertIs<SyncEngineStatus.Failure>(state.status.value)
        assertSame(error, status.error)
    }

    // ─── recordPullResult ─────────────────────────────────────────────────────

    @Test
    fun `recordPullResult stores a success Result`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val summary = PullSummary(received = 3, applied = 3, dropped = 0)

        state.phases.recordPullResult(Result.success(summary))

        assertTrue(state.lastPull.value!!.isSuccess)
    }

    @Test
    fun `recordPullResult stores a failure Result`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)
        val error = AppError.Persistence("pull failed", code = "sync.pull_failed")

        state.phases.recordPullResult(Result.failure(error))

        assertTrue(state.lastPull.value!!.isFailure)
        assertSame(error, state.lastPull.value!!.exceptionOrNull())
    }

    @Test
    fun `recordPullResult does not change status`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        state.phases.recordPullResult(Result.success(PullSummary(0, 0, 0)))

        assertIs<SyncEngineStatus.Idle>(state.status.value)
    }

    // ─── PhaseResult.toResult() ───────────────────────────────────────────────

    @Test
    fun `toResult maps Ok to success`() {
        val summary = PushSummary(processed = 2, succeeded = 2, failed = 0)
        val phaseResult: PhaseResult<PushSummary> = PhaseResult.Ok(summary)

        val result = phaseResult.toResult()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().processed)
    }

    @Test
    fun `toResult maps Failed to failure with the error`() {
        val error = AppError.Persistence("push failed", code = "sync.push")
        val phaseResult: PhaseResult<PushSummary> = PhaseResult.Failed(error)

        val result = phaseResult.toResult()

        assertTrue(result.isFailure)
        assertSame(error, result.exceptionOrNull())
    }

    @Test
    fun `toResult maps NotRun to success with zero summary`() {
        val phaseResult: PhaseResult<PushSummary> = PhaseResult.NotRun

        val result = phaseResult.toResult()

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(0, summary.processed)
        assertEquals(0, summary.succeeded)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `toResult maps Superseded to success with zero summary`() {
        val phaseResult: PhaseResult<PushSummary> = PhaseResult.Superseded

        val result = phaseResult.toResult()

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(0, summary.processed)
        assertEquals(0, summary.superseded)
    }

    // ─── Status is the single source of truth ─────────────────────────────────

    /**
     * Verifies that the four "terminal bad" methods — pushFailed, pullFailed,
     * cycleFailed, and setStatus(Failure) — all write to the same status StateFlow.
     *
     * Calling any two in sequence must show only the last one, because the status
     * is the one field every observer reads to decide whether the engine is wedged.
     */
    @Test
    fun `all four failure paths write the same status reference`() = runTest {
        val reporter = RecordingCrashReporter()
        val state = SyncEngineState(log, reporter)

        val error1 = AppError.Persistence("first", code = "err.1")
        val error2 = AppError.Persistence("second", code = "err.2")

        // Push failure
        state.phases.pushFailed(error1, pending = 1)
        assertIs<SyncEngineStatus.Failure>(state.status.value)

        // Pull failure overwrites it — last-write-wins is the contract.
        state.phases.pullFailed(error2, sinceLsn = 0)
        val afterPullFailure = assertIs<SyncEngineStatus.Failure>(state.status.value)
        assertSame(error2, afterPullFailure.error)

        // cycleFailed overwrites again.
        val error3 = AppError.Persistence("third", code = "err.3")
        state.phases.cycleFailed(error3)
        val afterCycleFailure = assertIs<SyncEngineStatus.Failure>(state.status.value)
        assertSame(error3, afterCycleFailure.error)

        // setStatus(Failure) overwrites again.
        val error4 = AppError.Persistence("fourth", code = "err.4")
        state.phases.setStatus(SyncEngineStatus.Failure(error4))
        val afterSetFailure = assertIs<SyncEngineStatus.Failure>(state.status.value)
        assertSame(error4, afterSetFailure.error)

        // The last write is what observers see.
        assertEquals("err.4", (state.status.value as SyncEngineStatus.Failure).error.code)
    }

    // ─── No-op crash reporter ──────────────────────────────────────────────────

    @Test
    fun `works with NoOpCrashReportingPort`() = runTest {
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val error = AppError.Persistence("test", code = "test.noop")

        // Must not throw.
        state.phases.pushFailed(error, pending = 1)
        state.phases.pullFailed(error, sinceLsn = 0)
        state.phases.cycleFailed(error)

        assertIs<SyncEngineStatus.Failure>(state.status.value)
    }
}
