package com.singularity.todo.core.coroutines

import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deadline the tests inject.
 *
 * Small enough to be obvious, and unlike the production value it costs nothing: the
 * bound is measured on the caller's clock, and the caller's clock here is virtual.
 */
private const val DEADLINE_MS = 50L

/**
 * A deadline that is nobody's default.
 *
 * Deliberately not a round number and not [DEADLINE_MS]: a bound that came from a
 * constant hidden inside the primitive would look exactly like the caller's own number
 * if that number were the obvious one.
 */
private const val A_CALLER_CHOSEN_DEADLINE_MS = 1_234L

/**
 * How long a read that ignores cancellation stays in the way.
 *
 * It has to outlast every deadline it is used against, or the read answers before the
 * deadline and the bound is never exercised.
 */
private const val BLOCK_MS = 60_000L

/**
 * A read that ignores cancellation, and then answers.
 *
 * This is the shape a deadline in the caller's own coroutine cannot end: a blocking
 * driver call. The [NonCancellable] block is what makes it a real test of the bound —
 * a read that merely suspended for a long time would be ended by any deadline at all,
 * so it would pass against an implementation that only looks bounded.
 */
private suspend fun blockingRead(): String {
    withContext(NonCancellable) {
        withTimeoutOrNull(BLOCK_MS) { awaitCancellation() }
    }
    return "late"
}

/**
 * The primitive under test: a read that runs in a scope of its own, and a caller that
 * waits on a promise of its answer.
 */
@Tag("fast")
class BoundedReadTest {

    /**
     * A scope of its own for the read, on the test's scheduler.
     *
     * The read runs outside the caller's coroutine — that is the whole point of the
     * primitive — so the scope needs a job of its own and the test's virtual time, or
     * the abandoned read would outlive the test in real time.
     *
     * The failure handler is there so a read that fails after the caller gave up on it
     * has somewhere to go: without one, the failure would reach the platform's default
     * handler and the test would fail for a reason it did not ask about.
     */
    private fun TestScope.readScope(): CoroutineScope =
        CoroutineScope(
            StandardTestDispatcher(testScheduler) +
                SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )

    @Test
    fun `a read that ignores cancellation is abandoned at the deadline`() = runTest {
        val scope = readScope()

        val outcome = scope.boundedRead(DEADLINE_MS) { blockingRead() }

        assertIs<BoundedReadResult.TimedOut>(outcome)
        assertEquals(
            DEADLINE_MS,
            testScheduler.currentTime,
            "the wait must end at the deadline: virtual time was ${testScheduler.currentTime} ms",
        )
    }

    @Test
    fun `a read that comes back in time is served`() = runTest {
        val scope = readScope()

        val outcome = scope.boundedRead(DEADLINE_MS) { "value" }

        val completed = assertIs<BoundedReadResult.Completed<String>>(outcome)
        assertEquals("value", completed.value)
        assertEquals(0L, testScheduler.currentTime, "a read that answers immediately waits for nothing")
    }

    @Test
    fun `the deadline is the one the caller passed`() = runTest {
        val scope = readScope()

        scope.boundedRead(A_CALLER_CHOSEN_DEADLINE_MS) { blockingRead() }

        assertEquals(
            A_CALLER_CHOSEN_DEADLINE_MS,
            testScheduler.currentTime,
            "the bound must be the caller's own number, not a constant hidden in the primitive",
        )
    }

    @Test
    fun `a read that fails reaches the caller`() = runTest {
        val scope = readScope()

        val result = runCatchingCancellable {
            scope.boundedRead(DEADLINE_MS) { throw IllegalStateException("read failed") }
        }

        val failure = result.exceptionOrNull()
        assertIs<IllegalStateException>(failure)
        assertEquals("read failed", failure.message)
    }

    @Test
    fun `a read that answers null is not a read that timed out`() = runTest {
        val scope = readScope()

        val outcome = scope.boundedRead(DEADLINE_MS) { null as String? }

        val completed = assertIs<BoundedReadResult.Completed<String?>>(outcome)
        assertNull(completed.value, "a read that answers null answers null")
    }

    @Test
    fun `a read that fails after the caller gave up on it is reported, not dropped`() = runTest {
        val failures = mutableListOf<Throwable>()
        val scope = readScope()

        val outcome = scope.boundedRead(
            timeoutMs = DEADLINE_MS,
            onAbandonedFailure = { failures += it },
        ) {
            blockingRead()
            throw IllegalStateException("failed late")
        }

        assertIs<BoundedReadResult.TimedOut>(outcome)
        assertTrue(failures.isEmpty(), "the failure happens after the deadline, not before it")

        // The abandoned read is still running: it fails only once the scheduler has
        // carried it past its own block.
        advanceUntilIdle()

        val failure = failures.singleOrNull()
        assertIs<IllegalStateException>(failure)
        assertEquals("failed late", failure.message)
    }
}
