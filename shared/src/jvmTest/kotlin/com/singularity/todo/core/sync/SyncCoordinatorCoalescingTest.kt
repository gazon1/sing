@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract of [SyncCoordinator] — the single owner of the sync cycle.
 *
 * REQ-OS-008: concurrent requests are coalesced such that at most one cycle is in
 * progress, plus at most one follow-up.
 *
 * These tests drive the real coordinator. The previous test suite
 * (`SyncRepositoryCoalescingTest`) asserted the behaviour of a *fake* that
 * duplicated the production guard in miniature, so it passed while the production
 * guard had a hole in it — the guard read `engine.status`, which the engine resets
 * between its push and pull phases, so a caller arriving in that window started a
 * second cycle. A test of a copy of the logic cannot find a bug in the logic.
 */
@Tag("fast")
class SyncCoordinatorCoalescingTest {

    private val success = SyncOutcome.Success(
        push = Result.success(PushSummary(1, 1, 0)),
        pull = Result.success(PullSummary(1, 1, 0)),
    )

    /** Records how many cycles ran and how many overlapped. */
    private class CycleRecorder {
        var started = 0
        var finished = 0
        var maxConcurrent = 0
        private var concurrent = 0

        suspend fun run(): SyncOutcome {
            started++
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                return SyncOutcome.Success(
                    push = Result.success(PushSummary(started, started, 0)),
                    pull = Result.success(PullSummary(started, started, 0)),
                )
            } finally {
                concurrent--
                finished++
            }
        }
    }

    private fun TestScope.scope(): AutoCloseableCoroutineScope =
        AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun `a single request runs exactly one cycle`() = runTest {
        val recorder = CycleRecorder()
        val coordinator = SyncCoordinator(runCycle = { recorder.run() }, scope = scope())

        coordinator.request()

        advanceUntilIdle()
        assertEquals(1, recorder.started)
    }

    @Test
    fun `cycles never overlap`() = runTest {
        val recorder = CycleRecorder()
        val coordinator = SyncCoordinator(runCycle = { recorder.run() }, scope = scope())

        val requests = (1..10).map { launch { coordinator.request() } }
        advanceUntilIdle()
        requests.forEach { it.join() }

        assertEquals(1, recorder.maxConcurrent, "two cycles ran at the same time")
    }

    @Test
    fun `ten concurrent requests produce at most one cycle plus one follow-up`() = runTest {
        val recorder = CycleRecorder()
        val coordinator = SyncCoordinator(runCycle = { recorder.run() }, scope = scope())

        // All ten land before the consumer gets to run: one cycle, then the single
        // conflated follow-up.
        val requests = (1..10).map { launch { coordinator.request() } }
        advanceUntilIdle()
        requests.forEach { it.join() }

        assertTrue(
            recorder.started <= 2,
            "expected at most 2 cycles for 10 requests, got ${recorder.started}",
        )
        assertTrue(recorder.started >= 1, "no cycle ran at all")
    }

    @Test
    fun `every requester is answered with an outcome, not a skip`() = runTest {
        val recorder = CycleRecorder()
        val coordinator = SyncCoordinator(runCycle = { recorder.run() }, scope = scope())

        val outcomes = (1..5).map { async { coordinator.request() } }
        advanceUntilIdle()
        val results = outcomes.map { it.await() }

        // The point of the redesign: a request absorbed into a running cycle is
        // answered with that cycle's result, not with "someone else is running".
        results.forEach { assertIs<SyncOutcome.Success>(it) }
    }

    @Test
    fun `a request arriving during a cycle is served by the follow-up, not dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val coordinator = SyncCoordinator(
            runCycle = {
                started++
                if (started == 1) gate.await()
                success
            },
            scope = scope(),
        )

        val first = async { coordinator.request() }
        runCurrent()
        assertEquals(1, started, "first cycle should be in flight")

        // A request lands mid-cycle. It must not be answered by the cycle that is
        // already running — it needs a cycle of its own, and the conflated channel
        // guarantees exactly one.
        val second = async { coordinator.request() }
        runCurrent()
        assertTrue(!second.isCompleted, "the mid-cycle request was answered by the running cycle")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, started)
        assertIs<SyncOutcome.Success>(first.await())
        assertIs<SyncOutcome.Success>(second.await())
    }

    @Test
    fun `a throwing cycle does not stop the coordinator`() = runTest {
        var started = 0
        val coordinator = SyncCoordinator(
            runCycle = {
                started++
                if (started == 1) error("boom") else success
            },
            scope = scope(),
        )

        val first = coordinator.request()
        assertIs<SyncOutcome.Success>(first)
        assertTrue(first.push.isFailure, "the failure should be reported, not swallowed")

        advanceUntilIdle()
        val second = coordinator.request()
        assertIs<SyncOutcome.Success>(second)
        assertEquals(2, started, "the second request never ran a cycle")
    }

    @Test
    fun `a waiter is released when the coordinator closes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = SyncCoordinator(
            runCycle = {
                gate.await()
                success
            },
            scope = scope(),
        )

        val waiter = async { coordinator.request() }
        runCurrent()
        assertTrue(!waiter.isCompleted)

        coordinator.close()
        advanceUntilIdle()

        // Without the release, this suspends forever.
        assertIs<SyncOutcome.Skipped>(waiter.await())
    }
}
