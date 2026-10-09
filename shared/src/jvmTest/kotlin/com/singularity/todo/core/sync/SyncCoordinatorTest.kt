package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for [SyncCoordinator] not covered by [SyncCoordinatorCoalescingTest].
 *
 * `SyncCoordinatorCoalescingTest` covers: coalescing, re-entry, waiter release on close,
 * throwing cycle survival, exception wrapping. This file covers:
 *
 * - `close()` called before any request → `NothingToDo`
 * - `lastOutcome` is set after each cycle and readable after `request()` returns
 * - Sequential cycles (one completes before the next starts)
 * - Exception from `runCycle` is wrapped as `CouldNotStart`, not `Unknown`
 */
@Tag("fast")
class SyncCoordinatorTest {

    private fun TestScope.scope(): AutoCloseableCoroutineScope =
        AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))

    // ─── close() before any request ───────────────────────────────────────

    @Test
    fun `close then request returns NothingToDo`() = runTest {
        val coordinator = SyncCoordinator(
            runCycle = { SyncOutcome.Completed(Result.success(PushSummary(0, 0, 0)), Result.success(PullSummary(0, 0, 0))) },
            scope = scope(),
        )

        coordinator.close()

        val outcome = coordinator.request()

        assertIs<SyncOutcome.NothingToDo>(outcome)
    }

    @Test
    fun `request after close returns NothingToDo even if cycles ran before close`() = runTest {
        var cycles = 0
        val coordinator = SyncCoordinator(
            runCycle = {
                cycles++
                SyncOutcome.Completed(Result.success(PushSummary(0, 0, 0)), Result.success(PullSummary(0, 0, 0)))
            },
            scope = scope(),
        )

        coordinator.request()
        advanceUntilIdle()
        assertEquals(1, cycles)

        coordinator.close()

        val outcome = coordinator.request()
        assertIs<SyncOutcome.NothingToDo>(outcome)
    }

    // ─── lastOutcome ───────────────────────────────────────────────────────

    /**
     * After a cycle completes, `lastOutcome` holds the outcome and remains available
     * to callers of `request()`.
     */
    @Test
    fun `lastOutcome is set after a successful cycle`() = runTest {
        val outcome = SyncOutcome.Completed(
            push = Result.success(PushSummary(1, 1, 0)),
            pull = Result.success(PullSummary(2, 2, 0)),
        )
        val coordinator = SyncCoordinator(runCycle = { outcome }, scope = scope())

        val result = coordinator.request()

        advanceUntilIdle()
        assertSame(outcome, result)
    }

    // ─── Sequential cycles ──────────────────────────────────────────────────

    @Test
    fun `sequential requests run one cycle each`() = runTest {
        var cycleCount = 0
        val coordinator = SyncCoordinator(
            runCycle = {
                cycleCount++
                SyncOutcome.Completed(Result.success(PushSummary(cycleCount, cycleCount, 0)), Result.success(PullSummary(0, 0, 0)))
            },
            scope = scope(),
        )

        coordinator.request()
        advanceUntilIdle()
        assertEquals(1, cycleCount)

        coordinator.request()
        advanceUntilIdle()
        assertEquals(2, cycleCount)
    }

    // ─── Exception wrapping ─────────────────────────────────────────────────

    /**
     * An exception thrown from runCycle is converted to `CouldNotStart(error)`,
     * where `error` is an AppError wrapping the original exception with its cause preserved.
     *
     * The previous implementation used `AppError.Unknown(e.message ?: "")` which discarded
     * the cause chain. This test verifies the fix.
     */
    @Test
    fun `runCycle exception is wrapped with the original cause preserved`() = runTest {
        val original = IllegalStateException("database is gone")
        val coordinator = SyncCoordinator(
            runCycle = { throw original },
            scope = scope(),
        )

        val outcome = coordinator.request()

        advanceUntilIdle()
        val failed = assertIs<SyncOutcome.CouldNotStart>(outcome)
        assertEquals("database is gone", failed.error.message)
        // The cause chain must be intact — the original throw is the cause of the AppError.
        assertSame(original, failed.error.cause)
        // And AppError.original() returns the bottom of the chain.
        assertSame(original, failed.error.original())
    }

    @Test
    fun `runCycle exception AppError has the correct code`() = runTest {
        val coordinator = SyncCoordinator(
            runCycle = { error("something bad happened") },
            scope = scope(),
        )

        val outcome = coordinator.request()

        advanceUntilIdle()
        val failed = assertIs<SyncOutcome.CouldNotStart>(outcome)
        assertEquals("error.unknown", failed.error.code)
    }

    // ─── close() is idempotent ───────────────────────────────────────────────

    @Test
    fun `close is idempotent — calling twice is safe`() = runTest {
        val coordinator = SyncCoordinator(
            runCycle = { SyncOutcome.Completed(Result.success(PushSummary(0, 0, 0)), Result.success(PullSummary(0, 0, 0))) },
            scope = scope(),
        )

        coordinator.close()
        coordinator.close() // Must not throw.

        val outcome = coordinator.request()
        assertIs<SyncOutcome.NothingToDo>(outcome)
    }
}
