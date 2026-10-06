@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.helpers

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlin.test.fail

/**
 * Waits for [predicate] to return true using virtual time, failing with a
 * descriptive message if the timeout is reached.
 *
 * Usage (inside [kotlinx.coroutines.test.runTest]):
 * ```
 * awaitState { vm.state.value is SettingsUiState.Content }
 * awaitState(timeoutMs = 3_000L) { vm.isReady }
 * ```
 *
 * @param timeoutMs Maximum virtual time to wait (default 5 s).
 * @param predicate Called with the current state each time the virtual clock advances.
 * @param spinGuardMillis Wall-clock backstop in milliseconds. Overridable only so a test can
 *   exercise the guard itself without waiting two minutes for it; production callers leave it.
 *
 * ## Why there is a spin bound as well as a timeout
 *
 * The obvious implementation is `while (!predicate()) advanceUntilIdle()`. It is a trap, and
 * this file is the second time it has cost real time.
 *
 * `advanceUntilIdle()` runs until there is nothing left to do. When the predicate waits on
 * state that will *never* arrive — a test that sets up "no calendar selected" and then awaits
 * `googleReady`, where ready is defined as *having* a calendar — there is nothing to do, so
 * it returns immediately, and virtual time has not moved. The loop condition is still false,
 * the deadline is still in the future, and nothing ever changes. Virtual time cannot advance
 * because nothing is scheduled, so the `currentTime > deadline` check is unreachable: the test
 * spins at 100% CPU until the whole suite is killed.
 *
 * A wall-clock guard catches what virtual time structurally cannot. It is deliberately
 * generous — this is a backstop against a hung suite, not a performance budget — and it fires
 * with the predicate's own condition so the failure names the state that never arrived rather
 * than reporting a bare timeout.
 */
fun TestScope.awaitState(
    timeoutMs: Long = 5_000L,
    spinGuardMillis: Long = SPIN_GUARD_MILLIS,
    predicate: () -> Boolean,
) {
    val deadline = currentTime + timeoutMs
    // Wall-clock backstop. System.nanoTime, not a coroutine delay: a delay would itself need
    // the scheduler to advance, which is the thing that is not happening.
    val spinDeadline = System.nanoTime() + spinGuardMillis * 1_000_000
    var iterations = 0L

    while (!predicate()) {
        if (currentTime > deadline) {
            fail(
                "Timeout after $timeoutMs ms; virtualTime = $currentTime. " +
                    "The awaited state never arrived — check the predicate against what the " +
                    "test actually set up.",
            )
        }
        // Counted in real iterations, not virtual milliseconds: each spin is one trip round
        // the loop, and that is what the wall-clock bound is measuring.
        if (System.nanoTime() > spinDeadline) {
            fail(
                "awaitState spun for ${spinGuardMillis}s of real time without the predicate " +
                    "ever becoming true and without virtual time advancing. This is the " +
                    "'nothing left to schedule' hang: advanceUntilIdle() returns instantly, so " +
                    "the virtual deadline can never be reached. The awaited state is probably " +
                    "unreachable from this test's setup. Iterations: $iterations, " +
                    "virtualTime = $currentTime.",
            )
        }
        iterations++
        advanceUntilIdle()
    }
}

/**
 * How long a single [awaitState] may spin before it is declared hung.
 *
 * Slow enough that a legitimately slow test — one under heavy CPU contention, say — is not
 * killed mid-run. Short enough that a hung suite fails in minutes rather than never: a full
 * `:shared:jvmTest` pass takes roughly that long when it works, so a single test spinning for
 * as long as the entire suite is unambiguously wrong.
 */
private const val SPIN_GUARD_MILLIS = 120_000L
