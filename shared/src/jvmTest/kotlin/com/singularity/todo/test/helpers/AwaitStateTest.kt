package com.singularity.todo.test.helpers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The guard that stops a mis-written test from hanging the whole suite.
 *
 * ## Why this exists
 *
 * `awaitState` waits by advancing virtual time. When the awaited state is unreachable, there
 * is nothing left to schedule, so `advanceUntilIdle()` returns instantly and virtual time
 * never moves — which makes the virtual-time deadline unreachable by construction. The naive
 * implementation spins at 100% CPU until the suite is killed.
 *
 * That happened: a Google-sync test set up "no calendar selected" and then awaited
 * `googleReady`, which is *defined* as having a calendar. It spun for over half an hour
 * across two runs before anyone read the stack trace, and it was read as CPU starvation for
 * most of that time. So the guard gets its own test — a guard nobody has exercised is a
 * guard nobody can trust.
 */
@Tag("fast")
class AwaitStateTest {

    /**
     * A predicate that becomes true is polled, and the guard does not interfere.
     *
     * The virtual-time deadline stays authoritative for work that *is* scheduled, and this
     * test plus the one below is the whole contract. There is deliberately no third case for
     * "a slow but scheduled wait", because there isn't one: with nothing left to schedule,
     * `advanceUntilIdle()` returns instantly and virtual time cannot move — which is exactly
     * the hang the guard catches. A predicate that eventually holds does so because
     * something was scheduled, and then this test's behaviour applies.
     */
    @Test
    fun `a predicate that becomes true does not trip the spin guard`() = runTest {
        var seen = 0
        awaitState(spinGuardMillis = 30_000L) {
            seen++
            seen > 3
        }
        assertTrue(seen > 3, "the predicate is polled until it holds")
    }

    /**
     * The case the guard exists for. A predicate that is never true, with nothing scheduled,
     * must fail with an explanation rather than spin.
     *
     * [spinGuardMillis] is 1 ms here, so this costs nothing — the guard is doing the same
     * arithmetic at a different scale, not a different code path.
     */
    @Test
    fun `an unreachable predicate fails instead of spinning forever`() = runTest {
        val message = try {
            awaitState(spinGuardMillis = 1L) { false }
            fail("expected the spin guard to fire")
        } catch (e: AssertionError) {
            e.message.orEmpty()
        }

        assertTrue(
            message.contains("spun"),
            "the failure must name the spin, not report a bare timeout: $message",
        )
        assertTrue(
            message.contains("unreachable"),
            "the failure must say the state was unreachable: $message",
        )
    }
}
