@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Contract of [DelayLoopSyncPeriodicTrigger] — the JVM periodic driver.
 *
 * The bug this class documents: the loop used to be
 * `while (isActive) { syncOnce(); delay(interval) }` with no guard, so one throwing
 * cycle killed the coroutine and auto-sync was dead for the rest of the process —
 * with no error anywhere, because a coroutine that ends quietly is
 * indistinguishable from one that was never started.
 *
 * The second bug this class prevents is a re-introduction of the one above: the JVM
 * never selected this path at all, because the platform decision was made by asking
 * whether an injected scheduler was a `NoOpSyncScheduler`, and the JVM module binds
 * `JvmSyncScheduler`. The loop existed, was documented as active, and had never run
 * once. That one is a wiring bug and is covered by
 * `SyncPeriodicTriggerWiringTest`.
 *
 * ## Two constraints on how these tests are written
 *
 * **The trigger runs in `backgroundScope`, never in a `TestScope` created inside the
 * test body.** A scope constructed in the test is not a child of anything: it is
 * still alive after the test method returns, so a loop left running survives into
 * the next test with nobody left to cancel it. `backgroundScope` is cancelled when
 * the test ends, which is the only reason a loop cannot outlive its test.
 *
 * **Virtual time is advanced by a bounded amount, never with `advanceUntilIdle()`.**
 * A self-rescheduling loop always has another `delay` queued, so "until idle" never
 * arrives: time runs away, and every iteration appends its logged failure to the test
 * output. The first version of the "permanently failing loop" test did this and
 * produced 69 GB of binary test output before anyone noticed.
 */
@Tag("fast")
class DelayLoopSyncPeriodicTriggerTest {

    @Test
    fun `fires once per interval`() = runTest {
        var fired = 0
        val trigger = DelayLoopSyncPeriodicTrigger(
            request = { fired++ },
            scope = backgroundScope,
        )

        trigger.start(5.minutes)
        runCurrent()
        assertEquals(1, fired, "the first cycle should fire immediately")

        advanceTimeBy(5.minutes + 1.seconds)
        runCurrent()
        assertEquals(2, fired, "exactly one cycle per interval, not more")

        trigger.stop()
    }

    @Test
    fun `a throwing cycle does not kill the loop`() = runTest {
        var fired = 0
        val trigger = DelayLoopSyncPeriodicTrigger(
            request = {
                fired++
                if (fired == 1) error("first cycle fails")
            },
            scope = backgroundScope,
        )

        trigger.start(5.minutes)
        runCurrent()
        assertEquals(1, fired)

        // If the exception had escaped, the coroutine would be dead and this is where
        // it shows up: the interval passes and nothing happens.
        advanceTimeBy(5.minutes + 1.seconds)
        runCurrent()
        assertEquals(2, fired, "the loop died on the first failure")

        trigger.stop()
    }

    @Test
    fun `a permanently failing loop keeps firing`() = runTest {
        var fired = 0
        val trigger = DelayLoopSyncPeriodicTrigger(
            request = {
                fired++
                error("always fails")
            },
            scope = backgroundScope,
        )

        trigger.start(1.minutes)
        // Cycles land at t=0, 60s and 120s, so advancing to 121s runs three of them.
        advanceTimeBy(2.minutes + 1.seconds)
        runCurrent()

        assertEquals(3, fired, "a failing loop must keep retrying, not give up")

        trigger.stop()
    }

    @Test
    fun `stop halts the loop`() = runTest {
        var fired = 0
        val trigger = DelayLoopSyncPeriodicTrigger(
            request = { fired++ },
            scope = backgroundScope,
        )

        trigger.start(1.minutes)
        runCurrent()
        val afterStart = fired

        trigger.stop()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(afterStart, fired, "the loop kept firing after stop()")
    }

    @Test
    fun `start twice does not leave two loops running`() = runTest {
        var fired = 0
        val trigger = DelayLoopSyncPeriodicTrigger(
            request = { fired++ },
            scope = backgroundScope,
        )

        trigger.start(5.minutes)
        trigger.start(5.minutes)
        runCurrent()

        // One loop, one cycle per interval. Two loops would be 2 here and 4 later.
        assertEquals(1, fired)
        advanceTimeBy(5.minutes + 1.seconds)
        runCurrent()
        assertEquals(2, fired)

        trigger.stop()
    }

    @Test
    fun `the loop is bounded by the interval, not by how long the test waits`() = runTest {
        // Pins the arithmetic the other tests depend on: if `delay` ever stopped
        // being virtual, this assertion is what fails first, loudly and cheaply,
        // instead of the whole suite filling the disk.
        var fired = 0
        val trigger = DelayLoopSyncPeriodicTrigger(
            request = { fired++ },
            scope = backgroundScope,
        )

        trigger.start(10.minutes)
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(2, fired, "10 minutes of virtual time is two 5-minute cycles")
        trigger.stop()
    }
}
