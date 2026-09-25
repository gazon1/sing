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
 */
fun TestScope.awaitState(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
    val deadline = currentTime + timeoutMs
    while (!predicate()) {
        if (currentTime > deadline) {
            fail("Timeout after $timeoutMs ms; virtualTime = $currentTime")
        }
        advanceUntilIdle()
    }
}
